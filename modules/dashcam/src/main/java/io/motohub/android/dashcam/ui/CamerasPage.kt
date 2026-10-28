// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.SavedCamera
import io.motohub.android.module.ModuleUi

/**
 * Every camera set up: the one in use marked, a tap on another to switch to it, a bin to forget
 * one, and the way to add a new one.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun CamerasPage(controller: DashcamController, open: (Page) -> Unit, back: () -> Unit) {
    val store = controller.store
    var cameras by remember { store.dropUnfinished(); mutableStateOf(store.cameras()) }
    var active by remember { mutableStateOf(store.active) }
    var removing by remember { mutableStateOf<SavedCamera?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            PageHeader("Cameras", back) {}
            if (cameras.isNotEmpty()) {
                Card {
                    cameras.forEachIndexed { i, c ->
                        if (i > 0) Hairline()
                        CameraRow(c, c.id == active, onPick = {
                            controller.switchTo(c.id)
                            active = c.id
                            back()
                        }, onRemove = { removing = c })
                    }
                }
            }
            Card {
                NavRow(Ico.CAMERA, "Add a camera", "Its own Wi-Fi, or on the phone's network", true) {
                    store.startNew()
                    open(Page.Setup)
                }
            }
            Text(
                "One camera at a time: picking another one leaves the one in use.",
                color = Dc.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        removing?.let { c ->
            ConfirmDialog(
                "Forget ${c.name}?",
                "Its Wi-Fi or address, and its login, are forgotten. Files already saved on the phone stay.",
                "Forget",
                { removing = null }
            ) {
                controller.removeCamera(c.id)
                cameras = store.cameras()
                active = store.active
                removing = null
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun CameraRow(c: SavedCamera, inUse: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).clickable(enabled = !inUse, onClick = onPick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (inUse) Dc.accentSoft else Dc.bg), contentAlignment = Alignment.Center) {
            DcIcon(if (c.lan) Ico.NETWORK else Ico.WIFI, if (inUse) Dc.accent else Dc.dim, 22.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Dc.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (c.lan) "Phone's network · " else "Own Wi-Fi · ") + c.where,
                color = Dc.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (inUse) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Dc.ok))
                Text("In use", color = Dc.ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            DcIcon(Ico.TRASH, Dc.faint, 20.dp)
        }
    }
}
