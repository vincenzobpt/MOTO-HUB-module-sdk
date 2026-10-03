// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.module.ModuleUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The camera full-screen. A tap shows the controls - status along the top, round buttons along
 * the bottom with the shutter in the middle - and they go again a few seconds later.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun LivePage(controller: DashcamController, open: (Page) -> Unit, back: () -> Unit) {
    val state by controller.state.collectAsState()
    val viewer = remember { controller.openLive() }
    val live by viewer.player.state.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var touches by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        onDispose { controller.closeLive(viewer) }
    }
    LaunchedEffect(Unit) {
        if (!state.connected && !state.busy) controller.connect()
    }
    LaunchedEffect(state.connected) {
        if (state.connected) controller.startLive() else controller.pauseLive()
    }
    LaunchedEffect(controls, touches) {
        if (controls) {
            delay(5000)
            controls = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val touch = { touches++ }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { controls = !controls; touches++ } },
        contentAlignment = Alignment.Center
    ) {
        val ratio = if (live.width > 0 && live.height > 0) live.width.toFloat() / live.height else 16f / 9f
        if (state.connected) VideoSurface(ratio) { controller.setLiveSurface(viewer, it) }

        if (!live.playing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DcIcon(Ico.CAMERA, Dc.faint, 40.dp)
                Text(
                    if (!state.connected) state.message.ifEmpty { "Connecting to the camera…" } else live.status.ifEmpty { "Opening the video…" },
                    color = Dc.dim, fontSize = 16.sp
                )
            }
        }

        if (controls) {
            Column(Modifier.fillMaxSize()) {
                // Status along the top.
                Row(
                    Modifier.fillMaxWidth().background(Dc.scrim).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassIconButton(Ico.BACK, Dc.text, Dc.glass, back)
                    Column(Modifier.weight(1f)) {
                        val name = state.identity?.let { listOf(it.maker, it.model).filter(String::isNotBlank).joinToString(" ") }.orEmpty()
                        Text(name.ifEmpty { "Dashcam" }, color = Dc.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (live.fps > 0) "Live · ${live.fps} fps" else "Live", color = Color(0xFFB8C3CE), fontSize = 13.sp)
                    }
                    val s = state.status
                    if (s?.recording == true) Pill("Recording", Dc.rec, Color(0x33FF5252), Dc.rec)
                    else Pill("Recording paused while you watch", Dc.warn, Dc.warnSoft, null)
                    s?.batteryPercent?.let { Pill("$it%", Dc.text, Color(0x1AFFFFFF), if (it < 20) Dc.rec else Dc.ok) }
                    if (s?.cardReady == false) Pill("No SD card", Dc.dangerText, Color(0x33E5484D), null)
                    else s?.cardFreeMb?.let { Pill(formatMb(it), Dc.text, Color(0x1AFFFFFF), Dc.accent) }
                }
                Spacer(Modifier.weight(1f))
                // Controls along the bottom, the shutter in the middle.
                Row(
                    Modifier.fillMaxWidth().background(Dc.scrim).padding(top = 12.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally)
                ) {
                    RoundButton("Files", { touch(); open(Page.Files(local = false)) }) { DcIcon(Ico.VIDEO, Dc.text, 26.dp) }
                    val recording = state.status?.recording == true
                    RoundButton(if (recording) "Stop rec" else "Record", {
                        touch()
                        controller.run(if (recording) "Recording stopped" else "Recording started", { p, h -> p.setRecording(h, !recording) }) { controller.refreshStatus() }
                    }) {
                        if (recording) Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Dc.rec))
                        else Box(Modifier.size(22.dp).clip(CircleShape).border(3.dp, Dc.rec, CircleShape))
                    }
                    Shutter { touch(); controller.run("Photo taken", { p, h -> p.snapshot(h) }) }
                    RoundButton("Settings", { touch(); open(Page.Settings) }) { DcIcon(Ico.SLIDERS, Dc.text, 26.dp) }
                    RoundButton("Lock clip", { touch(); controller.run("Clip locked", { p, h -> p.lockClip(h) }) }) { DcIcon(Ico.LOCK, Dc.text, 26.dp) }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(14.dp)) {
                Row(
                    Modifier.align(Alignment.TopStart).height(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0x8C060A0E)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (live.playing) Dc.accent else Dc.faint))
                    Text("LIVE", color = Dc.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).height(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0x8C060A0E)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now)), color = Dc.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The photo button: a white disc in a white ring, larger than the others. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Shutter(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(78.dp).clip(CircleShape).border(4.dp, Dc.text, CircleShape)
                .pointerInput(Unit) { detectTapGestures { onClick() } },
            contentAlignment = Alignment.Center
        ) { Box(Modifier.size(60.dp).clip(CircleShape).background(Dc.text)) }
        Text("Photo", color = Dc.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The decoder's surface, at the picture's own shape. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun VideoSurface(ratio: Float, onSurface: (android.view.Surface?) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxHeight().aspectRatio(ratio, true),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = onSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(h: SurfaceHolder) = onSurface(null)
                    })
                }
            }
        )
    }
}
