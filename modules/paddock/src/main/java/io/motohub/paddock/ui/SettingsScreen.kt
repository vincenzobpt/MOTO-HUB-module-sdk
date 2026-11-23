// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.EngineStage
import io.motohub.paddock.engine.PaddockController
import io.motohub.paddock.engine.PaddockState
import io.motohub.paddock.engine.Profile

/** The rider ID, the relay, and starting over. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun SettingsScreen(
    controller: PaddockController,
    state: PaddockState,
    profile: Profile?,
    slot: BackSlot,
    onEditProfile: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onRelays: () -> Unit,
    onBack: () -> Unit
) {
    var relay by remember { mutableStateOf(state.node.ifEmpty { controller.defaultRelay }) }
    var saved by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    val parsed = parseRelay(relay)
    val running = state.stage == EngineStage.RUNNING

    Box(Modifier.fillMaxSize()) {
        Page("Settings", onBack) {
            SectionLabel("Your rider ID")
            if (profile != null) Card {
                val color = riderColor(profile.color)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp).clickable(onClick = onEditProfile).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Avatar(profile.name, color, 48.dp)
                    Column(Modifier.weight(1f)) {
                        Txt(profile.name, Ty.BODY_STRONG, P.text, maxLines = 1)
                        Txt("Edit name, colour and bike", Ty.SMALL, P.dim, maxLines = 1)
                    }
                    PIcon(PI.CHEVRON, P.faint, 18.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card {
                NavRow(PI.KEY, "Back up my rider ID", "Keep it to move to a new phone, or to get it back after a reset", onBackup)
                Hairline(start = 70.dp)
                NavRow(PI.PASTE, "Restore a rider ID", "Replace this phone's rider ID with one you saved", onRestore)
            }
            if (state.myHash.isNotEmpty()) {
                Txt("Your Paddock address: ${grouped(state.myHash)}", Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, top = 10.dp))
            }

            Spacer(Modifier.height(S.section))
            SectionLabel("Connection")
            val alwaysOn by controller.alwaysOn.collectAsState()
            Card {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .clickable(role = Role.Switch) { controller.setAlwaysOn(!alwaysOn) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Txt("Always on", Ty.BODY_STRONG, P.text, maxLines = 1)
                        Txt(if (alwaysOn) "On: always reachable, uses more battery" else "Off: connected only when needed", Ty.SMALL, P.dim, maxLines = 2)
                    }
                    OnOffSwitch(alwaysOn)
                }
            }
            Txt(
                if (alwaysOn) "Paddock stays connected to the relays all the time, also with the screen off and MOTO-HUB closed. " +
                    "Messages, room invites and calls reach you at once.\n\n" +
                    "Paddock is a decentralised network with no central server: to stay reachable your phone keeps its " +
                    "connections to the relays open, and the relays keep sending it news from the whole network. " +
                    "This uses battery every hour, also when you are not riding. Turn it off if your battery drops too fast."
                else "Paddock connects to the relays only when it is needed: while you ride with a room, during a Paddock call, " +
                    "and while MOTO-HUB is on screen (and for one minute after you leave it). The rest of the time it uses almost no battery.\n\n" +
                    "Messages and room invites sent meanwhile wait in the mailbox and arrive when you open MOTO-HUB. " +
                    "Riders cannot call you until you open MOTO-HUB or start a ride.",
                Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, top = 10.dp)
            )

            // Shown only while Android blocks it: calls then ring without lighting the screen.
            var fullScreen by remember { mutableStateOf(controller.fullScreenCallsAllowed()) }
            LaunchedEffect(Unit) {
                while (true) { fullScreen = controller.fullScreenCallsAllowed(); kotlinx.coroutines.delay(2000) }
            }
            if (!fullScreen) {
                val context = androidx.compose.ui.platform.LocalContext.current
                Spacer(Modifier.height(S.section))
                SectionLabel("Calls")
                Card {
                    NavRow(PI.CALL, "Let calls light up the screen", "Android blocks it now. Tap, then allow full-screen notifications for MOTO-HUB") {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    android.net.Uri.parse("package:" + context.packageName))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(S.section))
            SectionLabel("Relays")
            Card {
                NavRow(PI.RELAY, "Relays", state.node.ifEmpty { "Automatic" } + " · on a map, and every relay heard of", onRelays)
            }

            Spacer(Modifier.height(S.section))
            SectionLabel("Start over")
            DangerButton("Forget my rider ID", PI.TRASH, filled = false, enabled = running) { forgetting = true }
            Txt(
                if (running) "Deletes your keys, your profile and any room from this phone."
                else "Available once Paddock has started.",
                Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, top = 8.dp)
            )

            Spacer(Modifier.height(36.dp))
            Txt("Paddock · free software under the Mozilla Public License 2.0", Ty.SMALL, P.faint, Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
        ConfirmSheet(
            forgetting, slot, PI.TRASH, danger = true,
            title = "Forget your rider ID?",
            body = "Paddock deletes your keys, your profile and your rooms from this phone. Rooms you lead are deleted " +
                "for everyone; you leave the others. This cannot be undone. Then close MOTO-HUB completely and open it again to make a new rider ID.",
            confirm = "Forget rider ID",
            onDismiss = { forgetting = false },
            onConfirm = { forgetting = false; controller.resetEverything() }
        )
    }
}

/** A plain on/off switch: the row around it takes the tap. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun OnOffSwitch(on: Boolean) {
    Box(
        Modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape)
            .background(if (on) P.accent else P.surfaceHi).border(1.dp, if (on) P.accent else P.line, CircleShape)
            .padding(4.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) P.accentInk else P.dim))
    }
}

/** "host:port" → host and port, or null when it is not one. */
private fun parseRelay(text: String): Pair<String, Int>? {
    val t = text.trim()
    val i = t.lastIndexOf(':')
    if (i <= 0 || i == t.length - 1) return null
    val host = t.substring(0, i)
    if (host.indexOf(' ') >= 0 || host.indexOf('/') >= 0) return null
    val port = try {
        Integer.parseInt(t.substring(i + 1))
    } catch (e: NumberFormatException) {
        return null
    }
    return if (port in 1..65535) Pair(host, port) else null
}

/** The first 16 hex digits in fours, enough to tell two rider IDs apart by eye. */
private fun grouped(hash: String): String {
    val out = StringBuilder()
    val n = if (hash.length < 16) hash.length else 16
    for (i in 0 until n) {
        if (i > 0 && i % 4 == 0) out.append(' ')
        out.append(hash[i])
    }
    return out.toString()
}
