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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.protocol.CameraFile
import io.motohub.android.dashcam.protocol.CameraFolder
import io.motohub.android.module.ModuleUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One tile of the grid: a camera file or a copy on the phone. */
private class Tile(val camera: CameraFile?, val local: File?, val writing: Boolean)

/**
 * The card's folders down the side, the files as a grid of tiles. The file the camera is still
 * writing is shown but cannot be opened: half-written, it cannot be played.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun FilesPage(controller: DashcamController, startLocal: Boolean, open: (Page) -> Unit, back: () -> Unit) {
    val state by controller.state.collectAsState()
    val folders = controller.currentProtocol?.folders.orEmpty()
    var folder by remember { mutableStateOf<CameraFolder?>(if (startLocal) null else folders.firstOrNull()) }
    var files by remember { mutableStateOf<List<CameraFile>?>(null) }
    var localVersion by remember { mutableStateOf(0) }
    val downloads by controller.downloads.collectAsState()
    val entered = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose { controller.run(null, { p, h -> if (entered.get()) p.exitFiles(h) }) }
    }
    LaunchedEffect(folder) {
        val f = folder ?: return@LaunchedEffect
        files = null
        controller.run(null, { p, h ->
            if (entered.compareAndSet(false, true)) {
                p.enterFiles(h)
                Thread.sleep(1500)
            }
            controller.refreshStatus()
            p.files(h, f)
        }) { files = it.getOrNull() ?: emptyList() }
    }
    LaunchedEffect(downloads.size) { localVersion++ }

    Row(Modifier.fillMaxSize()) {
        // The folders.
        Column(
            Modifier.width(212.dp).fillMaxHeight().background(Dc.rail).padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = back).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DcIcon(Ico.BACK, Dc.text, 20.dp)
                Text("Back", color = Dc.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            if (state.connected) folders.forEach { f ->
                RailItem(if (f.id == "photo" || f.label == "Photos") Ico.PHOTO else if (f.label == "Locked") Ico.LOCK else Ico.VIDEO, f.label, folder == f) { folder = f }
            }
            Spacer(Modifier.weight(1f))
            RailItem(Ico.PHONE, "On this phone", folder == null) { folder = null }
        }

        // The tiles.
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(folder?.label ?: "On this phone", color = Dc.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val tiles: List<Tile>? = if (folder == null) {
                remember(localVersion) { controller.localFiles() }.map { Tile(null, it, false) }
            } else files?.let { list ->
                val writing = if (state.status?.recording == true)
                    list.filter { !it.photo }.maxByOrNull { it.createdMillis }?.path else null
                list.map { Tile(it, null, it.path == writing) }
            }
            when {
                tiles == null -> Text("Asking the camera…", color = Dc.dim, fontSize = 15.sp)
                tiles.isEmpty() -> Text(
                    if (folder == null) "Nothing saved yet. Open a recording and choose Save to phone." else "No files here.",
                    color = Dc.dim, fontSize = 15.sp
                )
                else -> {
                    // Rows of four, built by hand: a lazy grid is not among what the app lends.
                    val rows = ArrayList<List<Tile>>()
                    var i = 0
                    while (i < tiles.size) { rows.add(tiles.subList(i, minOf(i + 4, tiles.size))); i += 4 }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(rows) { _, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { tile ->
                                    Box(Modifier.weight(1f)) {
                                        FileTile(tile, tile.camera?.path?.let { downloads[it] }) {
                                            if (!tile.writing) open(Page.Player(tile.camera, tile.local))
                                        }
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RailItem(icon: Ico, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Dc.accentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DcIcon(icon, if (selected) Dc.accent else Dc.text2, 18.dp)
        Text(label, color = if (selected) Dc.text else Dc.text2, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun FileTile(tile: Tile, saving: Float?, onClick: () -> Unit) {
    val camera = tile.camera
    val local = tile.local
    val name = camera?.name ?: local?.name.orEmpty()
    val photo = camera?.photo ?: name.substringAfterLast('.').lowercase(Locale.ROOT).let { it == "jpg" || it == "jpeg" || it == "png" }
    val created = camera?.createdMillis?.takeIf { it > 0 } ?: local?.lastModified() ?: 0L
    val size = camera?.sizeBytes ?: local?.length() ?: 0L
    Column(Modifier.fillMaxWidth().alpha(if (tile.writing) 0.85f else 1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = !tile.writing, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(98.dp).clip(RoundedCornerShape(12.dp))
                .background(if (tile.writing) Dc.surface else Color(0xFF2A3644))
                .then(if (tile.writing) Modifier.border(1.dp, Color(0xFF5A2A2D), RoundedCornerShape(12.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (tile.writing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Dc.rec))
                    Text("RECORDING", color = Dc.dangerText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                DcIcon(if (photo) Ico.PHOTO else Ico.PLAY, Color(0xFF8FA2B4), 28.dp)
                val duration = camera?.durationSeconds ?: 0
                if (duration > 0) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xBF060A0E)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(clock(duration * 1000L), color = Dc.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (saving != null) {
                    Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Dc.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(if (saving < 0) "SAVING" else "SAVING ${(saving * 100).toInt()}%", color = Dc.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(
            if (created > 0) SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(created)) else name,
            color = Dc.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(if (tile.writing) "Being written" else formatSize(size), color = Dc.dim, fontSize = 12.sp, maxLines = 1)
    }
}
