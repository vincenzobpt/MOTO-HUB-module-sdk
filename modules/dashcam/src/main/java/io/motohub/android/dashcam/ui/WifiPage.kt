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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.Phase
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.delay

/** The camera's Wi-Fi name and password, and the three steps of joining it, shown as they happen. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun WifiPage(controller: DashcamController, back: () -> Unit) {
    val state by controller.state.collectAsState()
    var ssid by remember { mutableStateOf(controller.store.ssid) }
    var password by remember { mutableStateOf(controller.store.password) }
    var asked by remember { mutableStateOf(false) }
    val onBike = remember { controller.motorcycleConnected() }

    // Back to the module page a moment after the camera answers: the last step is seen done.
    LaunchedEffect(state.connected, asked) {
        if (asked && state.connected) {
            delay(900)
            back()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        PageHeader("Connect the camera", back) {}
        Row(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(22.dp)).background(Dc.surface)
                .border(1.dp, Dc.surfaceLine, RoundedCornerShape(22.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally)
        ) {
            Box(Modifier.width(54.dp).height(92.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, Dc.dim, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.width(30.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Dc.dim))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until 3) Box(Modifier.size(8.dp).clip(CircleShape).background(if (i < 2 || state.connected) Dc.accent else Color(0xFF2A5568)))
            }
            Box(Modifier.width(84.dp).height(60.dp).clip(RoundedCornerShape(14.dp)).background(Dc.accentSoft), contentAlignment = Alignment.Center) {
                DcIcon(Ico.CAMERA, Dc.accent, 36.dp)
            }
        }
        Text(
            "The camera makes its own Wi-Fi. Its name and password are on a sticker on the camera or in its manual.",
            color = Dc.text2, fontSize = 15.sp, lineHeight = 22.sp
        )

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field("Wi-Fi name", ssid, false) { ssid = it }
            Field("Password (empty for an open network)", password, true) { password = it }
        }

        if (asked || state.busy || state.phase == Phase.FAILED) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dc.surface)
                    .border(1.dp, Dc.surfaceLine, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val joined = state.phase == Phase.FINDING || state.connected
                Step("Join ${ssid.ifBlank { "the camera" }}", done = joined, active = state.phase == Phase.JOINING)
                Step("Find the camera", done = state.connected, active = state.phase == Phase.FINDING)
                Step("Ready", done = state.connected, active = false)
                if (state.phase == Phase.FAILED && state.message.isNotEmpty()) {
                    Text(state.message, color = Dc.warn, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }

        Text(
            "The first time, Android asks whether MOTO-HUB may join this network: allow it. " +
                "Not while the phone is connected to the motorcycle.",
            color = Dc.dim, fontSize = 13.sp, lineHeight = 19.sp
        )
        BigButton(if (state.busy) "Connecting…" else "Connect", null, !state.busy && !onBike && ssid.isNotBlank(), false) {
            controller.store.ssid = ssid
            controller.store.password = password
            runCatching { controller.store.save() }
            asked = true
            // Already on a camera: leave it and join the one just entered, in that order.
            if (state.connected || state.busy) controller.reconnect() else controller.connect()
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Field(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Dc.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(Dc.surface)
                .border(1.dp, Dc.line, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Dc.text, fontSize = 18.sp),
                    cursorBrush = SolidColor(Dc.accent),
                    visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (secret) {
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).clickable { shown = !shown }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(if (shown) "Hide" else "Show", color = Dc.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Step(text: String, done: Boolean, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            done -> Box(Modifier.size(26.dp).clip(CircleShape).background(Dc.okSoft), contentAlignment = Alignment.Center) { DcIcon(Ico.CHECK, Dc.ok, 14.dp) }
            active -> Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, Dc.accent, CircleShape))
            else -> Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, Dc.line, CircleShape))
        }
        Text(if (active) "$text…" else text, color = if (done || active) Dc.text else Dc.faint, fontSize = 15.sp)
    }
}
