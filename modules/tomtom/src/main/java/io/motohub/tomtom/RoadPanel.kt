// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * When to ask TomTom about the road at the map's centre: only once the map rests close enough to
 * read a road, and only when it rests somewhere new. Pure, for the tests.
 */
internal class RoadLookout {

    private var askedLat = Double.NaN
    private var askedLon = Double.NaN

    /** Whether the map resting at ([lat], [lon]) and [zoom] is worth a call. */
    fun due(lat: Double, lon: Double, zoom: Double): Boolean {
        if (zoom < MIN_ZOOM) return false
        return askedLat.isNaN() || meters(askedLat, askedLon, lat, lon) >= MIN_MOVE_METERS
    }

    fun asked(lat: Double, lon: Double) {
        askedLat = lat
        askedLon = lon
    }

    /** A failed call is not remembered, so the same place is asked again on the next look. */
    fun forget() {
        askedLat = Double.NaN
        askedLon = Double.NaN
    }

    companion object {
        /** Under this the ring covers a whole neighbourhood: no single road to name. */
        const val MIN_ZOOM = 14.0
        const val MIN_MOVE_METERS = 20.0
    }
}

/**
 * Text colours for the panel. The host draws the sheet in the app's theme, light or dark, so
 * text follows the theme's own colours and the two warning colours darken on a light sheet.
 */
private class SheetInk(val text: Color, val muted: Color, val faint: Color, val gold: Color, val bad: Color, val chipFill: Color)

@Composable
private fun sheetInk(): SheetInk {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > 0.5f
    val text = scheme.onBackground
    return SheetInk(
        text, text.copy(alpha = 0.68f), text.copy(alpha = 0.5f),
        if (light) Color(0xFF946300) else Ink.Gold,
        if (light) Color(0xFFC62828) else Ink.Bad,
        text.copy(alpha = 0.09f)
    )
}

/**
 * The road under the ring at the centre of the NAV map: name, number and speed limit. It follows
 * the map, not the rider, so any road can be looked at before riding it. Nothing leaves this panel.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun RoadPanel(host: MotoHubModuleHost, service: TomTomService, onBack: () -> Unit) {
    val ink = sheetInk()
    val lookout = remember { RoadLookout() }
    var center by remember { mutableStateOf<DoubleArray?>(null) }
    var road by remember { mutableStateOf<Road?>(null) }
    // 0: not asked yet, 1: answered, 2: TomTom refused or did not answer ([problem] says why).
    var status by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }

    // Reads where the map rests twice a second while the panel is open; leaving the panel ends it.
    LaunchedEffect(Unit) {
        while (true) {
            val c = runCatching { host.places.mapCenter() }.getOrNull()?.takeIf { it.size >= 3 }
            center = c
            exhausted = service.ready && !service.usage.allowed(Api.REVERSE)
            if (c != null && service.on(Feature.ROAD) && !exhausted && lookout.due(c[0], c[1], c[2])) {
                lookout.asked(c[0], c[1])
                busy = true
                val answer = withContext(Dispatchers.IO) { service.roadAt(c[0], c[1]) }
                busy = false
                when (answer) {
                    is Answer.Ok -> { road = answer.value; status = 1; problem = null }
                    is Answer.Failed -> { lookout.forget(); road = null; status = 2; problem = answer.why }
                    is Answer.Exhausted -> exhausted = true
                }
            }
            delay(500)
        }
    }

    val c = center
    when {
        !service.ready || !service.isOn(Feature.ROAD) -> Calm(ink, if (service.ready) "Road info is switched off" else "Set up TomTom first") {
            SideButton("Open TomTom", true, Modifier) {
                host.openFeature(HOME_FEATURE)
                onBack()
            }
        }
        exhausted -> Calm(ink, "This month's road info is used up — back on the 1st.", null)
        c == null -> Calm(ink, "Open the NAV map to look at a road.", null)
        c[2] < RoadLookout.MIN_ZOOM -> Calm(ink, "Zoom in and put the ring on a road.", null)
        else -> Content(ink, road, status, busy, problem)
    }
}

/** One calm line, centred, with at most a button under it. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Calm(ink: SheetInk, line: String, button: (@Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Txt(line, 17.sp, ink.text, FontWeight.Medium, Modifier.fillMaxWidth(), 3, TextAlign.Center)
        button?.invoke()
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Content(ink: SheetInk, road: Road?, status: Int, busy: Boolean, problem: String?) {
    val name = road?.name ?: road?.ref ?: when {
        busy || status == 0 -> "Looking up the road…"
        status == 2 -> "TomTom did not answer"
        else -> "No road under the ring"
    }
    // The host's sheet already keeps 20 dp from the edges; only air above and below here.
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Roundel(road?.limitKmh)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(name, 22.sp, ink.text, FontWeight.Bold, Modifier, 2)
                // The number shows as a chip only beside a name; alone it already is the title.
                road?.ref?.takeIf { road.name != null }?.let { Chip(it, ink.text, ink.chipFill) }
                Txt(
                    when {
                        road == null && status == 2 && problem != null -> problem
                        road == null -> "Move the map: the ring at its centre is the point looked at."
                        road.limitKmh == null -> "No posted limit known here."
                        else -> "Posted limit ${road.limitKmh} km/h"
                    },
                    14.sp, ink.muted, FontWeight.Normal, Modifier, 2
                )
            }
        }
        Txt("At the centre of the map · Road data © TomTom", 12.sp, ink.faint, FontWeight.Normal, Modifier.fillMaxWidth().padding(top = 12.dp), 1)
    }
}

/** The speed limit as it stands on the road: a white disc in a red ring. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun Roundel(limit: Int?) {
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = size.minDimension * 0.09f
            drawCircle(Color.White)
            drawCircle(Color(0xFFD7261E), radius = size.minDimension / 2 - ring / 2, style = Stroke(ring))
        }
        // Three digits (110, 130) need a size smaller to stay inside the ring, as on the real sign.
        val size = if ((limit ?: 0) >= 100) 29.sp else 34.sp
        Txt(limit?.toString() ?: "—", size, Color.Black, FontWeight.Bold, Modifier.fillMaxWidth(), 1, TextAlign.Center)
    }
}

