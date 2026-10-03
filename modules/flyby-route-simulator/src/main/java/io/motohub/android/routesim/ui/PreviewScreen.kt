// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The preview: the generated ride on the map, coloured by speed, and its four charts.
package io.motohub.android.routesim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleMap
import io.motohub.android.module.ModuleUi

private val ALTITUDE_COLOR = Color(0xFF6DB3FF)
private val LEAN_COLOR = Color(0xFFFF8AD0)
private val RPM_COLOR = Color(0xFF4FD08A)

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PreviewScreen(env: RsEnv, model: PreviewModel, onBack: () -> Unit) {
    val map: ModuleMap? = remember { env.openMap() }
    DisposableEffect(map) {
        onDispose {
            if (map != null) env.closeMap(map)
        }
    }

    val series = model.series
    val topKph = remember(series) {
        var top = 0f
        for (v in series.speedKph) if (!v.isNaN() && v > top) top = v
        ChartMath.niceCeil(Math.max(top, 40f))
    }
    val index = ChartMath.cursorIndex(series.timesSeconds, model.cursor)

    // The ride's line is drawn once per variant; only the cursor's pin moves with the finger.
    LaunchedEffect(map, series) {
        if (map != null) {
            map.setLines(MapScenes.previewLines(series, topKph))
            map.fit(series.latitudes, series.longitudes)
        }
    }
    LaunchedEffect(map, series, index) {
        map?.setPins(MapScenes.previewPins(series, index))
    }

    val flyby = env.flybyInstalled
    val stats = model.stats
    val times = series.timesSeconds
    val total = if (times.isEmpty()) 0f else times[times.size - 1] - times[0]
    val at = if (times.isEmpty()) 0f else times[index] - times[0]

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(Strings.PREVIEW_TITLE, onBack) { }
        Box(Modifier.fillMaxWidth().weight(0.34f).background(RsColors.Surface)) {
            if (map != null) {
                Box(Modifier.fillMaxSize()) { map.Surface() }
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(0.66f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card {
                Txt(model.prepared.title, size = 18, weight = FontWeight.Bold, maxLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(Strings.FACT_DISTANCE, Fmt.distance(stats.distanceMeters), Modifier.weight(1f))
                    StatTile(Strings.FACT_DURATION, Fmt.duration(stats.durationSeconds), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(Strings.FACT_AVERAGE, Fmt.speed(stats.averageKph), Modifier.weight(1f))
                    StatTile(Strings.FACT_TOP, Fmt.speed(stats.topKph), Modifier.weight(1f))
                }
            }
            Txt(
                Fmt.clock(at) + " / " + Fmt.clock(total),
                color = RsColors.Accent, size = 18, weight = FontWeight.Bold
            )
            RideChart(Strings.CHART_SPEED, ChartKind.SPEED, series, series.speedKph, RsColors.Accent, model.cursor) { model.cursor = it }
            RideChart(Strings.CHART_ALTITUDE, ChartKind.ALTITUDE, series, series.altitudeM, ALTITUDE_COLOR, model.cursor) { model.cursor = it }
            RideChart(Strings.CHART_LEAN, ChartKind.LEAN, series, series.leanDeg, LEAN_COLOR, model.cursor) { model.cursor = it }
            RideChart(Strings.CHART_RPM, ChartKind.RPM, series, series.rpm, RPM_COLOR, model.cursor) { model.cursor = it }
            Txt(Strings.PREVIEW_HINT, color = RsColors.Dim, size = 13)
        }
        Column(
            Modifier.fillMaxWidth().background(RsColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model.busy) {
                    Txt(model.busyText, color = RsColors.Accent, size = 15, weight = FontWeight.Bold)
                } else if (model.message.isNotEmpty()) {
                    Txt(model.message, color = if (model.messageIsError) RsColors.Bad else RsColors.Ok, size = 14, maxLines = 3)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(Strings.REGENERATE, Modifier.weight(1f), !model.busy, false) { model.regenerate() }
                val saved = model.savedRideId != null
                BigButton(
                    if (saved) Strings.SAVED else if (model.busy && model.busyText == Strings.PROGRESS_SAVING) Strings.SAVING else Strings.SAVE,
                    Modifier.weight(1f), !model.busy && !saved, !saved
                ) { model.save() }
            }
            if (model.savedRideId != null) {
                BigButton(
                    if (flyby == true) Strings.OPEN_IN_FLYBY else Strings.FLYBY_MISSING,
                    Modifier.fillMaxWidth(), !model.busy && flyby == true, true
                ) { model.openInFlyby() }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RsShape12).background(RsColors.Surface2).padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Txt(value, size = 18, weight = FontWeight.Bold, maxLines = 1, align = TextAlign.Center)
        Txt(label, color = RsColors.Dim, size = 13, maxLines = 1, align = TextAlign.Center)
    }
}
