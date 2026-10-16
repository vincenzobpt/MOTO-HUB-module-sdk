// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import android.content.Context
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.delay

/**
 * The viewer's one video player: ExoPlayer reading Immich's own stream (`GET
 * /api/assets/{id}/video/playback`, asset.view) with the key in the header, as the browser would.
 * Its state is Compose state, so the page and the bar redraw as it plays. Main thread only.
 */
@OptIn(UnstableApi::class)
internal class VideoPlayback(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    /** The video loaded, or null. */
    var id by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    /** A first frame is on screen: until then the preview shows instead of a black box. */
    var drawn by mutableStateOf(false)
        private set
    var ratio by mutableStateOf<Float?>(null)
        private set
    var muted by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
        private set

    // Where each video was left, so swiping back and playing again goes on from there.
    private val left = HashMap<String, Long>()

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) duration = player.duration.coerceAtLeast(0L)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onRenderedFirstFrame() {
                drawn = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                problem = when (val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode) {
                    null -> "This video could not be played."
                    401 -> "Immich refused the key."
                    403 -> "The key may not play videos: give it asset.view in Immich."
                    else -> "Immich answered $code."
                }
            }
        })
        // A video with sound takes the audio from music or navigation while it plays, as any player does.
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
    }

    /** Starts [videoId] from where it was left. The id passed [ImmichServer.details], so it is safe in a path. */
    fun play(server: ImmichServer, videoId: String) {
        stop()
        id = videoId
        val root = server.base
        val key = server.apiKey.trim()
        if (root == null || key.isEmpty() || key.any { it.code < 0x21 || it.code > 0x7E }) {
            problem = "Check the address and the key in the module's settings."
            return
        }
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("x-api-key" to key))
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
        player.setMediaSource(
            ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri("$root/api/assets/$videoId/video/playback")),
            left[videoId] ?: 0L
        )
        player.playWhenReady = true
        player.prepare()
    }

    /** Play or pause; at the end, again from the start. */
    fun toggle() {
        when {
            ended -> { player.seekTo(0L); player.play() }
            player.playWhenReady -> player.pause()
            else -> player.play()
        }
    }

    fun pause() = player.pause()

    fun seek(millis: Long) {
        player.seekTo(millis)
        position = millis
    }

    fun mute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }

    /** Unloads the video, remembering where it was: swiped away, back to the grid. */
    fun stop() {
        id?.let { if (!ended) left[it] = player.currentPosition }
        player.stop()
        player.clearMediaItems()
        id = null
        playing = false
        buffering = false
        ended = false
        drawn = false
        ratio = null
        problem = null
        position = 0L
        duration = 0L
    }

    fun release() = player.release()
}

/**
 * The surface a video is drawn on. A TextureView, not a SurfaceView: the page slides and zooms
 * through graphicsLayer, and only a TextureView moves with it. Hidden (the app sent to the
 * background, the page taken off screen), the video pauses instead of playing on unseen.
 */
internal class VideoSurface(context: Context, private val onHidden: () -> Unit) : TextureView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) onHidden()
    }
}

/** Play or pause, the time gone, the bar to seek on, the whole length and the sound: dark glass over the film. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun VideoBar(video: VideoPlayback, modifier: Modifier) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(video.id) {
        while (true) {
            if (dragged == null) video.position = video.player.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }
    val total = video.duration
    val shownAt = dragged?.let { (it * total).toLong() } ?: video.position
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassKey(if (video.playing) "❚❚" else "▶", false) { video.toggle() }
        Txt(clock(shownAt), 14.sp, Color.White, FontWeight.SemiBold, Modifier.padding(horizontal = 6.dp), 1)
        SeekBar(
            fraction = if (total > 0) (shownAt.toFloat() / total).coerceIn(0f, 1f) else 0f,
            enabled = total > 0,
            onDrag = { dragged = it },
            onSeek = { video.seek((it * total).toLong()); dragged = null },
            modifier = Modifier.weight(1f)
        )
        Txt(if (total > 0) clock(total) else "–:––", 14.sp, Color(0xCCFFFFFF), FontWeight.SemiBold, Modifier.padding(horizontal = 6.dp), 1)
        GlassKey(if (video.muted) "Sound" else "Mute", video.muted) { video.mute() }
    }
}

/** A thin line with a knob: tapped or dragged to a point of the video. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun SeekBar(fraction: Float, enabled: Boolean, onDrag: (Float?) -> Unit, onSeek: (Float) -> Unit, modifier: Modifier) {
    var at by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.height(40.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { at = (it.x / size.width).coerceIn(0f, 1f); onDrag(at) },
                    onDragEnd = { onSeek(at) },
                    onDragCancel = { onDrag(null) }
                ) { change, _ ->
                    change.consume()
                    at = (change.position.x / size.width).coerceIn(0f, 1f)
                    onDrag(at)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
        Row(Modifier.widthIn(min = 14.dp).fillMaxWidth(fraction), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ink.Accent))
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** Milliseconds as "0:12" or "1:02:03". */
private fun clock(millis: Long): String = Words.duration((millis / 1000).toString())
