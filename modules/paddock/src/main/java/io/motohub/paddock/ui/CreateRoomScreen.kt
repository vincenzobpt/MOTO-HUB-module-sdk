// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.PaddockController
import io.motohub.paddock.engine.PaddockEvent
import io.motohub.paddock.engine.Room
import java.util.Locale

/**
 * Name the room, see what happens next, open it. The new room's screen takes over as soon as the
 * engine lists a room I lead that was not there at the tap; until then a waiting line.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun CreateRoomScreen(controller: PaddockController, rooms: List<Room>, onCreated: (String) -> Unit, onBack: () -> Unit) {
    var name by remember { mutableStateOf(defaultRoomName()) }
    var before by remember { mutableStateOf<Set<String>?>(null) }
    val opening = before != null
    LaunchedEffect(controller) {
        controller.events.collect { if (it is PaddockEvent.Error) before = null }
    }
    LaunchedEffect(rooms, before) {
        val known = before ?: return@LaunchedEffect
        for (r in rooms) if (r.iAmLeader && !known.contains(r.id)) {
            onCreated(r.id)
            return@LaunchedEffect
        }
    }

    Page(
        "Open a room", onBack,
        bottom = {
            if (opening) WaitingRow("Opening the room…", "Your phone is creating the room and its QR code.")
            else PrimaryButton("Open room", PI.FLAG, name.isNotBlank()) {
                before = roomIds(rooms)
                controller.createRoom(name.trim())
            }
        }
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
            PIcon(PI.FLAG, P.accent, 30.dp)
        }
        Spacer(Modifier.height(16.dp))
        Txt("Name the group", Ty.TITLE, P.text)
        Txt("A room is a group you ride with again and again. Every rider in it sees its name.", Ty.BODY, P.dim)
        Spacer(Modifier.height(20.dp))
        Field("Room name", name, { name = it }, "Sunday group", 40)
        Spacer(Modifier.height(S.section))
        SectionLabel("What happens next")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Step(1, "Your phone makes the room and its QR code.")
            Step(2, "Your riders scan it with Paddock, and your phone lets them in. It must be online when they scan.")
            Step(3, "The room stays on every rider's phone. When you ride together, tap Start ride: positions go to this room until you stop.")
        }
    }
}

/** A numbered line. The number sits in a plain disc: it is not a button. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(P.surfaceHi), contentAlignment = Alignment.Center) {
            Txt(number.toString(), Ty.SMALL_STRONG, P.accent)
        }
        Txt(text, Ty.BODY, P.text2, Modifier.weight(1f).padding(vertical = 2.dp))
    }
}

/** "Saturday group", after today. */
private fun defaultRoomName(): String =
    java.time.LocalDate.now().dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " group"
