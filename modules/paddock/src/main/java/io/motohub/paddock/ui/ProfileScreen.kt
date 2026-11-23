// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.paddock.engine.Profile
import io.motohub.paddock.engine.RiderColors

/**
 * The rider ID's face: a name (required), a colour for the map, a bike (optional). The first time
 * it creates the ID; later it edits it. Limits match what the engine keeps (32 and 48 characters).
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun ProfileScreen(current: Profile?, onSave: (Profile) -> Unit, onBack: () -> Unit) {
    val first = current == null
    var name by remember { mutableStateOf(current?.name ?: "") }
    var color by remember { mutableStateOf(current?.color ?: RiderColors[0]) }
    var bike by remember { mutableStateOf(current?.bike ?: "") }
    val ready = name.isNotBlank()
    val shown = riderColor(color)

    Page(
        if (first) "Your rider ID" else "Edit profile", onBack,
        bottom = {
            PrimaryButton(if (first) "Save and continue" else "Save changes", PI.CHECK, ready) {
                onSave(Profile(name.trim(), color, bike.trim()))
            }
        }
    ) {
        // How the others will see me, updating as I type.
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CallingRings(shown, Modifier.size(150.dp)) { Avatar(name.ifBlank { "?" }, shown, 88.dp) }
            Txt(name.trim().ifEmpty { "Your name" }, Ty.TITLE, if (ready) P.text else P.faint, maxLines = 1)
            Txt(bike.trim().ifEmpty { "Your bike" }, Ty.SMALL, P.dim, maxLines = 1)
        }
        Spacer(Modifier.height(S.section))
        Field("Name", name, { name = it }, "Your first name or nickname", 32)
        Txt("Shown to the riders of your rooms.", Ty.SMALL, P.dim, Modifier.padding(start = 4.dp, top = 6.dp))
        Spacer(Modifier.height(24.dp))
        SectionLabel("Your colour on the map")
        ColorPicker(color) { color = it }
        Spacer(Modifier.height(24.dp))
        Field("Bike (optional)", bike, { bike = it }, "CFMOTO 800MT Explore", 48)
        Spacer(Modifier.height(S.section))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(P.okSoft), contentAlignment = Alignment.Center) {
                PIcon(PI.KEY, P.ok, 22.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt("Your rider ID is a pair of keys made on this phone.", Ty.BODY_STRONG, P.text)
                Txt(
                    "Paddock never sends the secret key anywhere; only you can back it up. There is no account: your name, colour and bike " +
                        "are sent only to the riders of your rooms, encrypted.",
                    Ty.SMALL, P.dim
                )
            }
        }
    }
}

/** The rider colours, two rows of four; the chosen one is ringed and ticked. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ColorPicker(selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in 0..1) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0..3) {
                    val i = row * 4 + col
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (i < RiderColors.size) {
                            val hex = RiderColors[i]
                            val c = riderColor(hex)
                            val on = hex == selected
                            Box(
                                Modifier.size(60.dp).clip(CircleShape)
                                    .border(2.5.dp, if (on) P.text else Color.Transparent, CircleShape)
                                    .clickable { onPick(hex) }
                                    .semantics { contentDescription = (if (i < RiderColorNames.size) RiderColorNames[i] else "Colour") + if (on) ", chosen" else "" }
                                    .padding(6.dp).clip(CircleShape).background(c),
                                contentAlignment = Alignment.Center
                            ) {
                                if (on) PIcon(PI.CHECK, inkOn(c), 24.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
