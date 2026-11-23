// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.Call
import io.motohub.paddock.engine.CallStage
import io.motohub.paddock.engine.PaddockController
import kotlinx.coroutines.delay

/** The microphone is allowed; if not, Android's own question is put to the rider (tap again after). */
internal fun micReady(context: Context): Boolean {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
    context.activity()?.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4712)
    return false
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Over every Paddock screen while a call is on: ringing, calling or connected. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun CallScreen(controller: PaddockController, call: Call) {
    val context = LocalContext.current
    val audio by controller.callAudio.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.stage) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    val status = when (call.stage) {
        CallStage.CALLING -> "Calling…"
        CallStage.RINGING_OUT -> "Ringing…"
        CallStage.RINGING_IN -> "Paddock call"
        CallStage.CONNECTED -> call.connectedAt?.let { clock((now - it) / 1000) } ?: "Connected"
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val who: @Composable () -> Unit = {
        val avatar = if (landscape) 88.dp else 112.dp
        if (call.stage == CallStage.CONNECTED) Avatar(call.name, P.accent, avatar)
        else CallingRings(P.accent, Modifier.size(if (landscape) 150.dp else 200.dp)) { Avatar(call.name, P.accent, avatar) }
        Spacer(Modifier.height(if (landscape) 12.dp else 20.dp))
        Txt(call.name, Ty.TITLE, P.text, align = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Txt(status, Ty.BODY, if (call.stage == CallStage.CONNECTED) P.ok else P.text2, align = TextAlign.Center)
        if (call.remoteMuted) {
            Spacer(Modifier.height(6.dp))
            Txt("${call.name} muted their microphone", Ty.SMALL, P.warn, align = TextAlign.Center)
        }
        if (call.stage == CallStage.CONNECTED && audio.headset && !audio.speaker) {
            Spacer(Modifier.height(6.dp))
            Txt("On your headset", Ty.SMALL, P.dim, align = TextAlign.Center)
        }
    }
    val buttons: @Composable () -> Unit = {
        when (call.stage) {
            CallStage.RINGING_IN -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigButton(PI.HANG_UP, "Decline", P.danger) { controller.declineCall() }
                BigButton(PI.CALL, "Answer", P.ok) { if (micReady(context)) controller.answerCall() }
            }
            CallStage.CONNECTED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BigButton(if (audio.muted) PI.MIC_OFF else PI.MIC, if (audio.muted) "Unmute" else "Mute",
                        if (audio.muted) P.warn else P.surfaceHi) { controller.setMuted(!audio.muted) }
                    BigButton(PI.SPEAKER, if (audio.speaker) "Speaker on" else "Speaker",
                        if (audio.speaker) P.accent else P.surfaceHi) { controller.setSpeaker(!audio.speaker) }
                }
                Spacer(Modifier.height(if (landscape) 20.dp else 28.dp))
                BigButton(PI.HANG_UP, "Hang up", P.danger) { controller.hangUp() }
            }
            else -> BigButton(PI.HANG_UP, "Cancel", P.danger) { controller.hangUp() }
        }
    }
    // Taps stop here: the room under the call must not react to them.
    val base = Modifier.fillMaxSize().background(P.bg)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        .statusBarsPadding().navigationBarsPadding()
    if (landscape) {
        // Side by side: who on the left, the buttons on the right, both centred in their half.
        Row(base.padding(horizontal = 32.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { who() }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
        }
    } else {
        Column(base.padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            who()
            Spacer(Modifier.weight(1f))
            buttons()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun BigButton(icon: PI, label: String, bg: Color, size: Dp = 72.dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) { PIcon(icon, inkOn(bg), 30.dp) }
        Spacer(Modifier.height(8.dp))
        Txt(label, Ty.SMALL, P.text2)
    }
}

private fun clock(s: Long): String = "%d:%02d".format(s / 60, s % 60)
