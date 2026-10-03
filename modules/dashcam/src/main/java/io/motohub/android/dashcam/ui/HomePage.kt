// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.Phase
import io.motohub.android.module.ModuleUi

/**
 * The module's own page: the camera at a glance, the one thing to do next, and the ways to its
 * settings, its files and the connection.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun HomePage(controller: DashcamController, open: (Page) -> Unit, back: () -> Unit) {
    val state by controller.state.collectAsState()
    val onBike = remember { controller.motorcycleConnected() }
    val hasCamera = controller.store.ssid.isNotBlank()
    val local = remember(state.phase) { controller.localFiles() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PageHeader("Dashcam", back) { StatusChip(state.phase) }

        // The camera at a glance.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Dc.surface)
                .border(1.dp, Dc.surfaceLine, RoundedCornerShape(22.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Dc.accentSoft), contentAlignment = Alignment.Center) {
                    DcIcon(Ico.CAMERA, Dc.accent, 34.dp)
                }
                Column(Modifier.weight(1f)) {
                    val name = state.identity?.let { listOf(it.maker, it.model).filter(String::isNotBlank).joinToString(" ") }.orEmpty()
                    Text(
                        name.ifEmpty { if (hasCamera) controller.store.ssid else "No camera yet" },
                        color = Dc.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            state.identity != null -> state.family + (state.identity?.firmware?.takeIf { it.isNotBlank() }?.let { " firmware · $it" } ?: "") +
                                if (state.experimental) " · experimental" else ""
                            hasCamera -> "Not connected"
                            else -> "Set up a Wi-Fi dashcam to see it here"
                        },
                        color = Dc.dim, fontSize = 13.sp, maxLines = 2
                    )
                }
            }
            val s = state.status
            if (state.connected && s != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Modifier.weight(1f), "BATTERY", s.batteryPercent?.toString() ?: "–", if (s.batteryPercent != null) "%" else "",
                        (s.batteryPercent ?: 0) / 100f, if ((s.batteryPercent ?: 100) < 20) Dc.rec else Dc.ok)
                    val total = s.cardTotalMb
                    val free = s.cardFreeMb
                    StatTile(Modifier.weight(1f), "SD CARD",
                        if (s.cardReady == false) "None" else free?.let { if (it >= 1024) String.format(java.util.Locale.ROOT, "%.1f", it / 1024.0) else "$it" } ?: "OK",
                        if (s.cardReady == false) "" else free?.let { if (it >= 1024) " GB" else " MB" } ?: "",
                        if (total != null && total > 0 && free != null) 1f - free.toFloat() / total else 0f, Dc.accent)
                    RecordingTile(Modifier.weight(1f), s.recording) {
                        val on = s.recording == true
                        controller.run(if (on) "Recording stopped" else "Recording started", { p, h -> p.setRecording(h, !on) }) { controller.refreshStatus() }
                    }
                }
            }
        }

        when {
            onBike -> Notice("The phone is connected to the motorcycle. Disconnect from it in the Ride tab first: the phone has one Wi-Fi connection, and the camera needs it.")
            state.phase == Phase.FAILED && state.message.isNotEmpty() -> Notice(state.message)
        }

        when {
            !hasCamera -> BigButton("Set up the camera", Ico.CAMERA, !onBike, false) { open(Page.Wifi) }
            state.connected -> BigButton("Open the live view", Ico.PLAY, true, false) { open(Page.Live) }
            state.busy -> BigButton(state.message.ifEmpty { "Connecting…" }, null, false, false) {}
            else -> BigButton("Connect to the camera", Ico.WIFI, !onBike, false) { controller.connect() }
        }

        if (hasCamera) {
            Column {
                SectionTitle("The camera")
                Card {
                    NavRow(Ico.SLIDERS, "Camera settings", "Microphone, codec, length of each file", state.connected) { open(Page.Settings) }
                    Hairline()
                    NavRow(Ico.VIDEO, "Files on the card", "Videos and photos on the camera", state.connected) { open(Page.Files(local = false)) }
                    Hairline()
                    NavRow(Ico.PHONE, "Saved on this phone",
                        if (local.isEmpty()) "Nothing saved yet" else "${local.size} file${if (local.size == 1) "" else "s"} · ${formatSize(local.fold(0L) { a, f -> a + f.length() })}",
                        true) { open(Page.Files(local = true)) }
                }
            }
            Column {
                SectionTitle("Connection")
                Card {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        DcIcon(Ico.WIFI, Dc.accent, 22.dp)
                        Column(Modifier.weight(1f)) {
                            Text(controller.store.ssid, color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (state.connected) "${state.address} · Wi-Fi held awake" else "The camera's own Wi-Fi", color = Dc.dim, fontSize = 13.sp)
                        }
                        SmallButton("Change") { open(Page.Wifi) }
                    }
                    if (state.connected) {
                        Hairline()
                        Row(Modifier.fillMaxWidth().height(54.dp).clickable { controller.disconnect() }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Disconnect", color = Dc.text2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Works when the phone is not connected to the motorcycle - with the dashboard on the phone, for example. " +
                "While you watch, the camera stops recording so the picture stays smooth; it records again when you close the live view.",
            color = Dc.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun StatusChip(phase: Phase) {
    val (label, fg, bg) = when (phase) {
        Phase.CONNECTED -> Triple("Connected", Dc.ok, Dc.okSoft)
        Phase.JOINING, Phase.FINDING -> Triple("Connecting", Dc.accent, Dc.accentSoft)
        Phase.FAILED -> Triple("Not connected", Dc.warn, Dc.warnSoft)
        Phase.IDLE -> Triple("Offline", Dc.dim, Dc.surface)
    }
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun StatTile(modifier: Modifier, label: String, value: String, unit: String, fill: Float, color: Color) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Dc.bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Dc.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        // Never wrapped: squeezed by a long value, the unit used to stand on end a letter per line.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Dc.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            if (unit.isNotEmpty()) Text(unit, color = Dc.dim, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 3.dp))
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Dc.line)) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RecordingTile(modifier: Modifier, recording: Boolean?, onToggle: () -> Unit) {
    // A button, not just a reading: recording is switched on and off from here as well as from the live view.
    val on = recording == true
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color(0x1FFF5252) else Dc.bg)
            .border(1.dp, if (on) Color(0x66FF5252) else Dc.line, RoundedCornerShape(14.dp))
            .clickable(enabled = recording != null, onClick = onToggle).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("RECORDING", color = Dc.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1, softWrap = false)
        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (on) Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Dc.rec))
            else Box(Modifier.size(12.dp).clip(CircleShape).border(2.dp, Dc.rec, CircleShape))
            Text(when (recording) { true -> "ON"; false -> "OFF"; null -> "–" }, color = Dc.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        Text(if (on) "Tap to stop" else "Tap to start", color = if (on) Dc.dangerText else Dc.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Notice(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dc.warnSoft).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(Dc.warn))
        Text(text, color = Dc.text, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SmallButton(text: String, onClick: () -> Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Dc.line, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Dc.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
