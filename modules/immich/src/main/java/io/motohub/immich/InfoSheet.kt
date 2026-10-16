// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleMapPin
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import java.util.Locale

/** Everything Immich knows about the open photo, over the lower part of it. A drag down or ✕ puts it away. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
@OptIn(ExperimentalLayoutApi::class)
internal fun InfoSheet(
    host: MotoHubModuleHost,
    server: ImmichServer,
    id: String,
    d: PhotoDetails?,
    problem: String?,
    sharing: Boolean,
    onShare: () -> Unit,
    onClose: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.86f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Ink.Ground)
                // The sheet's own background takes its taps: they must not reach the photo under it.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Box(
                Modifier.fillMaxWidth().pointerInput(Unit) {
                    var pulled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { pulled = 0f },
                        onDragEnd = { if (pulled > 80f) onClose() }
                    ) { _, amount -> pulled += amount }
                }.padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.Faint))
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                Header(d, onClose)
                when {
                    d == null && problem != null -> Txt(problem, 15.sp, Ink.Bad, FontWeight.Medium, Modifier.padding(top = 12.dp), 4)
                    d == null -> Txt("Asking Immich…", 15.sp, Ink.Muted, FontWeight.Medium, Modifier.padding(top = 12.dp), 1)
                }
                if (d != null) Body(host, d)
                Spacer(Modifier.height(20.dp))
                val lat = d?.latitude
                val lon = d?.longitude
                if (lat != null && lon != null) {
                    // The app's own route preview: the rider still chooses to start, as for any destination.
                    MainButton("Navigate here", true, Modifier.fillMaxWidth()) {
                        runCatching { host.navigateTo(lat, lon, d.place ?: d.fileName ?: "Photo") }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                server.webUrl(id)?.let { link ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SideButton(if (sharing) "Preparing…" else "Share", !sharing, Modifier.weight(1f), onShare)
                        SideButton("Open in Immich", true, Modifier.weight(1f)) {
                            runCatching {
                                host.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
@OptIn(ExperimentalLayoutApi::class)
private fun Header(d: PhotoDetails?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Txt(d?.takenAt?.let { Words.day(it) } ?: "Photo", 22.sp, Ink.Text, FontWeight.Bold, Modifier, 2)
            val time = listOfNotNull(d?.takenAt?.let { Words.time(it) }, d?.timeZone).joinToString("  ·  ")
            if (time.isNotEmpty()) Txt(time, 15.sp, Ink.Muted, FontWeight.Medium, Modifier.padding(top = 2.dp), 1)
            d?.place?.let { Txt(it, 15.sp, Ink.Accent, FontWeight.SemiBold, Modifier.padding(top = 4.dp), 2) }
        }
        GlassKey("✕", false, onClose)
    }
    if (d == null) return
    val badges = listOfNotNull(
        if (d.video) "▶ Video" + (d.duration?.let { " · ${Words.duration(it)}" } ?: "") else null,
        "♥ Favourite".takeIf { d.favorite },
        d.rating?.let { "★".repeat(it) + "☆".repeat(5 - it) },
        "Live photo".takeIf { d.livePhoto },
        "Panorama".takeIf { d.panorama },
        "Edited".takeIf { d.edited },
        "Archived".takeIf { d.archived },
        "In the trash".takeIf { d.trashed }
    )
    if (badges.isNotEmpty()) {
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            badges.forEach { Chip(it, if (it.startsWith("★") || it.startsWith("♥")) Ink.Gold else Ink.Text, Ink.PanelHigh) }
        }
    }
    d.description?.let {
        Txt("“$it”", 16.sp, Ink.Text, FontWeight.Normal, Modifier.padding(top = 14.dp), 8)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
@OptIn(ExperimentalLayoutApi::class)
private fun Body(host: MotoHubModuleHost, d: PhotoDetails) {
    val lat = d.latitude
    val lon = d.longitude
    Eyebrow("Where", Modifier)
    if (lat != null && lon != null) {
        PlaceMap(host, lat, lon)
        Spacer(Modifier.height(10.dp))
        LinesCard {
            InfoLine("Place", d.place)
            InfoLine("Coordinates", String.format(Locale.ROOT, "%.6f, %.6f", lat, lon))
        }
    } else {
        LinesCard { InfoLine("Position", "The photo carries none") }
    }

    val settings = listOfNotNull(
        d.fNumber?.let { "ƒ/" + Words.trim(it) to "Aperture" },
        d.exposure?.let { Words.exposure(it) to "Shutter" },
        d.iso?.let { it.toString() to "ISO" },
        d.focalLength?.let { Words.trim(it) + " mm" to "Focal length" }
    )
    if (d.camera != null || d.lens != null || settings.isNotEmpty()) {
        Eyebrow("Camera", Modifier)
        Column(Modifier.fillMaxWidth().card().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            d.camera?.let { Txt(it, 18.sp, Ink.Text, FontWeight.Bold, Modifier, 2) }
            d.lens?.let { Txt(it, 14.sp, Ink.Muted, FontWeight.Medium, Modifier, 2) }
            if (settings.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.forEach { (value, label) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Ink.PanelHigh).padding(vertical = 12.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Txt(value, 16.sp, Ink.Text, FontWeight.Bold, Modifier, 1)
                            Txt(label, 11.sp, Ink.Faint, FontWeight.SemiBold, Modifier.padding(top = 2.dp), 1)
                        }
                    }
                }
            }
        }
    }

    if (d.people.isNotEmpty()) Chips("People", d.people)
    if (d.albums.isNotEmpty()) Chips("Albums", d.albums.map { (name, count) -> count?.let { "$name · $it" } ?: name })
    if (d.tags.isNotEmpty()) Chips("Tags", d.tags.map { "#$it" })

    Eyebrow("File", Modifier)
    LinesCard {
        InfoLine("Name", d.fileName)
        InfoLine(
            "Resolution",
            if (d.width != null && d.height != null) {
                "${d.width} × ${d.height}  ·  " + String.format(Locale.ROOT, "%.1f MP", d.width.toLong() * d.height / 1_000_000.0)
            } else null
        )
        InfoLine("Size", d.fileSize?.let { Words.bytes(it) })
        InfoLine("Format", d.mimeType)
        InfoLine("Orientation", d.orientation?.let { Words.orientation(it) })
        InfoLine("Taken", d.takenAt?.let { Words.moment(it) })
        InfoLine("Uploaded", d.uploadedAt?.let { Words.moment(it) })
        InfoLine("File changed", d.fileModifiedAt?.let { Words.moment(it) })
    }

    Eyebrow("Immich", Modifier)
    LinesCard {
        InfoLine("Owner", d.owner)
        InfoLine("Visibility", d.visibility?.lowercase()?.replaceFirstChar { it.uppercase() })
        InfoLine("On the server", d.originalPath)
        InfoLine("Id", d.id)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
@OptIn(ExperimentalLayoutApi::class)
private fun Chips(title: String, values: List<String>) {
    Eyebrow(title, Modifier)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { Chip(it, Ink.Text, Ink.PanelHigh) }
    }
}

/** The app's own map, held on the spot the photo was taken. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun PlaceMap(host: MotoHubModuleHost, lat: Double, lon: Double) {
    val map = remember { runCatching { host.maps.open() }.getOrNull() }
    DisposableEffect(map) {
        onDispose { runCatching { map?.close() } }
    }
    LaunchedEffect(map, lat, lon) {
        runCatching {
            map?.setPins(arrayOf(ModuleMapPin(lat, lon, PIN_COLOR, "")))
            map?.center(lat, lon, 14.5)
        }
    }
    Box(Modifier.fillMaxWidth().height(230.dp).card(radius = 20.dp), contentAlignment = Alignment.Center) {
        if (map != null) map.Surface() else Txt("The map is not available", 14.sp, Ink.Faint, FontWeight.Medium, Modifier, 1)
    }
}
