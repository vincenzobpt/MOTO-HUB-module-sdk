// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.QuickKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The six one-tap messages, two to a row, in the order of the design. */
private val TILES = listOf(QuickKind.STOP, QuickKind.FUEL, QuickKind.SLOW, QuickKind.WAIT, QuickKind.PHOTO, QuickKind.SPOT)

private const val HOLD_NANOS = 2_000_000_000L

/**
 * Quick messages for riding: big tiles, one tap each, sent to everyone in [roomName]. Emergency
 * stands apart and needs a 2-second hold, its fill showing how far along it is: a glove brushing
 * the screen must not send it. [onSend] sends and closes; the caller says so with a toast.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun QuickSheet(visible: Boolean, slot: BackSlot, roomName: String, onSend: (QuickKind) -> Unit, onDismiss: () -> Unit) {
    Sheet(visible, slot, "Quick messages", onDismiss) {
        Txt("One tap, sent to everyone in $roomName", Ty.SMALL, P.dim, Modifier.padding(bottom = 16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in 0..2) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (col in 0..1) {
                        val kind = TILES[row * 2 + col]
                        QuickTile(kind, Modifier.weight(1f)) { onSend(kind) }
                    }
                }
            }
            EmergencyTile { onSend(QuickKind.EMERGENCY) }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun QuickTile(kind: QuickKind, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(104.dp).clip(RoundedCornerShape(22.dp)).background(P.surfaceHi).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        PIcon(quickIcon(kind), quickTint(kind), 30.dp)
        Txt(kind.label, Ty.HEADING, P.text, maxLines = 1)
    }
}

/** Held for 2 s it sends; let go earlier and nothing is sent, and a short tap says to hold. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun EmergencyTile(onSend: () -> Unit) {
    val send by rememberUpdatedState(onSend)
    val scope = rememberCoroutineScope()
    var hold by remember { mutableFloatStateOf(0f) }
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) {
            delay(2500)
            hint = false
        }
    }
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxWidth().height(80.dp).clip(shape).background(P.dangerSoft).border(1.dp, P.danger.copy(alpha = 0.6f), shape)
            .semantics { contentDescription = "Emergency. Hold for 2 seconds to send it with your position." }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    var sent = false
                    val run = scope.launch {
                        val start = withFrameNanos { it }
                        while (!sent) {
                            withFrameNanos { now ->
                                hold = ((now - start).toFloat() / HOLD_NANOS).let { if (it > 1f) 1f else it }
                            }
                            if (hold >= 1f) {
                                sent = true
                                send()
                            }
                        }
                    }
                    tryAwaitRelease()
                    run.cancel()
                    if (!sent) {
                        if (hold < 0.15f) hint = true
                        hold = 0f
                    }
                })
            }
    ) {
        // The fill grows left to right while the finger stays down.
        Box(Modifier.fillMaxWidth(hold).fillMaxHeight().background(P.danger.copy(alpha = 0.35f)))
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PIcon(PI.WARNING, P.dangerText, 30.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt("Emergency", Ty.HEADING, P.dangerText)
                Txt(
                    when {
                        hint -> "Hold to send"
                        hold > 0f -> "Keep holding…"
                        else -> "Hold 2 seconds · adds your position when the GPS has one"
                    },
                    Ty.SMALL, P.dangerText
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
