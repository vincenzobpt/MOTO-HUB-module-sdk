// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/** "What the map shows": favourites only, when, which album. One choice per row; [onChange] after each. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
@OptIn(ExperimentalLayoutApi::class)
internal fun MapFilterCard(server: ImmichServer, onChange: () -> Unit) {
    var f by remember { mutableStateOf(server.filter) }
    var years by remember { mutableStateOf<List<Int>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var albumProblem by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (server.configured) years = withContext(Dispatchers.IO) { server.years() }
    }
    LaunchedEffect(picking) {
        if (picking && albums == null) {
            withContext(Dispatchers.IO) {
                runCatching { server.albums() }
                    .onSuccess { albums = it; albumProblem = null }
                    .onFailure { albumProblem = it.message ?: "Immich did not list the albums." }
            }
        }
    }

    fun choose(next: MapFilter) {
        f = next
        server.filter = next
        server.save()
        onChange()
    }

    Column(Modifier.fillMaxWidth().card().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable { choose(f.copy(favourites = !f.favourites)) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Favourites only", 16.sp, Ink.Text, FontWeight.SemiBold, Modifier, 1)
                Txt("The photos with a heart in Immich", 13.sp, Ink.Muted, FontWeight.Normal, Modifier, 2)
            }
            Spacer(Modifier.width(12.dp))
            Toggle(f.favourites)
        }

        Rule()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("When", 16.sp, Ink.Text, FontWeight.SemiBold, Modifier, 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pick("Any time", f.period == MapFilter.ANY_TIME) { choose(f.copy(period = MapFilter.ANY_TIME)) }
                Pick("Last 12 months", f.period == MapFilter.RECENT) { choose(f.copy(period = MapFilter.RECENT)) }
                years.forEach { y -> Pick(y.toString(), f.period == y.toString()) { choose(f.copy(period = y.toString())) } }
            }
        }

        Rule()
        Row(Modifier.fillMaxWidth().clickable { picking = !picking }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Album", 16.sp, Ink.Text, FontWeight.SemiBold, Modifier, 1)
                Txt(f.albumName ?: "Any album", 13.sp, Ink.Muted, FontWeight.Normal, Modifier, 2)
            }
            Spacer(Modifier.width(12.dp))
            Txt(if (picking) "Close" else "Choose", 15.sp, Ink.Accent, FontWeight.Bold, Modifier, 1)
        }
        if (picking) {
            val list = albums
            when {
                albumProblem != null -> Txt(albumProblem!!, 14.sp, Ink.Bad, FontWeight.Medium, Modifier, 4)
                list == null -> Txt("Asking Immich for the albums…", 14.sp, Ink.Muted, FontWeight.Normal, Modifier, 1)
                else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.PanelHigh)) {
                    AlbumRow("Any album", null, f.albumId == null) {
                        picking = false
                        choose(f.copy(albumId = null, albumName = null))
                    }
                    list.forEach { a ->
                        AlbumRow(a.name, a.count, f.albumId == a.id) {
                            picking = false
                            choose(f.copy(albumId = a.id, albumName = a.name))
                        }
                    }
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun AlbumRow(name: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(name, 15.sp, if (on) Ink.Accent else Ink.Text, if (on) FontWeight.Bold else FontWeight.Medium, Modifier.weight(1f), 1)
        count?.let { Txt(NumberFormat.getIntegerInstance().format(it), 13.sp, Ink.Faint, FontWeight.Normal, Modifier.padding(start = 10.dp), 1) }
        if (on) Txt("✓", 16.sp, Ink.Accent, FontWeight.Bold, Modifier.padding(start = 10.dp), 1)
    }
}

/** A chip that is one of a set of choices: filled when chosen. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Pick(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)) {
        Chip(text, if (on) Color.White else Ink.Muted, if (on) Ink.AccentDeep else Ink.PanelHigh)
    }
}

/** An on/off switch; the row around it takes the tap. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Toggle(on: Boolean) {
    Box(
        Modifier.size(width = 50.dp, height = 30.dp).clip(RoundedCornerShape(50))
            .background(if (on) Ink.AccentDeep else Ink.PanelHigh).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) Color.White else Ink.Faint))
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Line))
}
