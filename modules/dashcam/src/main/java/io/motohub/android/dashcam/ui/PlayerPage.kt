// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.view.SurfaceView
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.protocol.CameraFile
import io.motohub.android.dashcam.video.CameraDataSource
import io.motohub.android.module.ModuleUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * One recording or photo, full-screen: the file's time and size along the top with Save and
 * Delete, play and the timeline along the bottom. Videos on the camera play through ExoPlayer
 * reading the camera directly; a copy on the phone plays from its file.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PlayerPage(controller: DashcamController, camera: CameraFile?, local: File?, back: () -> Unit) {
    val context = LocalView.current.context
    val downloads by controller.downloads.collectAsState()
    val name = camera?.name ?: local?.name.orEmpty()
    val photo = camera?.photo ?: name.substringAfterLast('.').lowercase(Locale.ROOT).let { it == "jpg" || it == "jpeg" || it == "png" }
    val created = camera?.createdMillis?.takeIf { it > 0 } ?: local?.lastModified() ?: 0L
    val size = camera?.sizeBytes ?: local?.length() ?: 0L
    var confirmDelete by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }

    val http = remember { controller.cameraHttp() }
    val player = remember {
        if (photo) null else ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 2_500, 5_000).build())
            .build()
    }
    var ready by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var ratio by remember { mutableStateOf(16f / 9f) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }

    if (player != null) DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && !ready) {
                    ready = true
                    duration = player.duration.coerceAtLeast(0L)
                    controller.log("play: ready, ${player.videoSize.width}x${player.videoSize.height}, $duration ms, audio ${player.audioFormat?.sampleMimeType ?: "none"}")
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onPlayerError(error: PlaybackException) {
                controller.log("play: ExoPlayer failed on $name: ${error.errorCodeName} ${error.cause?.message ?: error.message}")
                failed = "This file could not be played."
            }
        }
        player.addListener(listener)
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        when {
            local != null -> player.setMediaItem(MediaItem.fromUri(Uri.fromFile(local)))
            camera != null && http != null -> player.setMediaSource(
                ProgressiveMediaSource.Factory(CameraDataSource.Factory(http, controller::log))
                    .createMediaSource(MediaItem.fromUri(CameraDataSource.uriFor(camera.path)))
            )
            else -> failed = "Not connected to the camera."
        }
        if (failed == null) {
            player.playWhenReady = true
            player.prepare()
        }
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    if (photo && camera != null) LaunchedEffect(camera.path) {
        controller.run(null, { _, h -> h.get(camera.path, 15_000).body }) { photoBytes = it.getOrNull() }
    }
    LaunchedEffect(ready) {
        while (ready && player != null) {
            if (dragging == null) position = player.currentPosition
            delay(500)
        }
    }
    LaunchedEffect(chrome, playing) {
        if (chrome && playing) {
            delay(4000)
            chrome = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { chrome = !chrome } }, contentAlignment = Alignment.Center) {
        if (player != null) {
            Box(Modifier.fillMaxHeight().aspectRatio(ratio, true)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } })
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { view ->
                    view.setImageBitmap(local?.let { BitmapFactory.decodeFile(it.absolutePath) } ?: photoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
                }
            )
        }
        val message = failed
        if (message != null || (player != null && (!ready || buffering))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message ?: if (!ready) "Reading the file from the camera…" else "Loading…", color = Dc.dim, fontSize = 16.sp)
                if (message != null && camera != null) SmallButton("Save to the phone instead") { controller.download(camera) }
            }
        }

        if (chrome || !playing) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Dc.scrim).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassIconButton(Ico.BACK, Dc.text, Dc.glass, back)
                    Column(Modifier.weight(1f)) {
                        Text(if (created > 0) SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(created)) else name,
                            color = Dc.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(if (duration > 0) clock(duration) else "", formatSize(size), name).filter { it.isNotEmpty() }.joinToString(" · "),
                            color = Color(0xFFB8C3CE), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (camera != null) {
                        val saving = downloads[camera.path]
                        Row(
                            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Dc.glass)
                                .clickable(enabled = saving == null) { controller.download(camera) }.padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DcIcon(Ico.DOWNLOAD, Dc.text, 20.dp)
                            Text(when { saving == null -> "Save to phone"; saving < 0 -> "Saving…"; else -> "Saving ${(saving * 100).toInt()}%" },
                                color = Dc.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    GlassIconButton(Ico.TRASH, Dc.dangerText, Color(0x2EE5484D)) { confirmDelete = true }
                }
                Spacer(Modifier.weight(1f))
                if (player != null && ready && failed == null) {
                    Row(
                        Modifier.fillMaxWidth().background(Dc.scrim).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(Modifier.size(60.dp).clip(CircleShape).background(Dc.text).clickable { if (player.isPlaying) player.pause() else player.play() },
                            contentAlignment = Alignment.Center) {
                            DcIcon(if (playing) Ico.PAUSE else Ico.PLAY, Dc.accentInk, 24.dp)
                        }
                        Text(clock(dragging?.toLong() ?: position), color = Dc.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = dragging ?: position.toFloat(),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { target -> player.seekTo(target.toLong()); position = target.toLong() }
                                dragging = null
                            },
                            valueRange = 0f..maxOf(1L, duration).toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                        Text(clock(duration), color = Color(0xFFB8C3CE), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (confirmDelete) {
            ConfirmDialog(
                if (camera != null) "Delete this file from the camera?" else "Delete this copy from the phone?",
                if (camera != null) "It is erased from the camera's card. A copy saved on this phone is kept." else "The copy on this phone is removed. The camera's card is not touched.",
                "Delete",
                onCancel = { confirmDelete = false }
            ) {
                confirmDelete = false
                if (camera != null) controller.run("Deleted from the camera", { p, h -> p.delete(h, camera) }) { if (it.isSuccess) back() }
                else { local?.delete(); controller.say("Deleted from the phone"); back() }
            }
        }
    }
}
