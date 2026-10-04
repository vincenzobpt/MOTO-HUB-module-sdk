// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The Experiences page: the country, the filters, and ten rides to choose from, each with the
// silhouette of its road. No live map: a card is drawn from the catalogue and gets its real numbers
// when its route has been planned.
package io.motohub.android.routesim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleUi
import io.motohub.android.routesim.experiences.ExperienceFilters
import kotlinx.coroutines.delay

private val RsShape8 = RoundedCornerShape(8.dp)

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun ExperiencesScreen(
    env: RsEnv,
    model: ExperiencesModel,
    onOpen: (OpenPlan) -> Unit,
    onByHand: () -> Unit,
    onPlans: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { model.start() }
    val opening = model.openingName
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(Strings.EXPERIENCES_TITLE, onBack) {
            TextAction(Strings.PLANS_BUTTON, RsColors.Accent, opening == null, onPlans)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (model.loading) {
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    Txt(Strings.LOADING_RIDES, color = RsColors.Dim, size = 15)
                }
            } else {
                ExperienceList(model, onOpen)
            }
        }
        Column(
            Modifier.fillMaxWidth().background(RsColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (opening != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        Strings.planningExperience(opening), modifier = Modifier.weight(1f),
                        color = RsColors.Accent, size = 15, weight = FontWeight.Bold, maxLines = 2
                    )
                    TextAction(Strings.CANCEL, RsColors.Text2, true) { model.cancelOpen() }
                }
            } else {
                BigButton(Strings.PLAN_BY_HAND, Modifier.fillMaxWidth(), true, false, onByHand)
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ExperienceList(model: ExperiencesModel, onOpen: (OpenPlan) -> Unit) {
    val filters = model.filters
    val ranked = model.ranked
    val busy = model.busyIds
    val results = model.results
    val banner = ExperiencesLogic.banner(ranked, model.alts, filters, results)
    val views = ArrayList<CardView>()
    for (r in ranked) {
        val alt = model.alts[r.experience.id] ?: 0
        views.add(ExperiencesLogic.cardView(r, filters, alt, results[r.experience.id], r.experience.id in busy))
    }
    val idle = model.openingName == null

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (banner != ExperiencesLogic.Banner.NONE) {
            item(key = "banner") {
                OfflineBanner(
                    if (banner == ExperiencesLogic.Banner.OFFLINE) Strings.OFFLINE_BANNER else Strings.DETOURS_BANNER
                ) { model.retry() }
            }
        }
        item(key = "countries") { CountryRow(model) }
        item(key = "filters") { FiltersCard(model) }
        if (model.pack == null || ranked.isEmpty()) {
            item(key = "empty") { Card { Txt(Strings.CATALOGUE_EMPTY, color = RsColors.Dim, size = 15) } }
        }
        // Keyed by id: a card that moves when the filters re-rank keeps its place in the composition.
        itemsIndexed(views, key = { _, v -> v.id }) { index, view ->
            ExperienceCard(
                view, idle,
                { ranked.getOrNull(index)?.let { model.open(it, onOpen) } },
                { model.otherIdeas(view.id) },
                { id, on -> model.markVisible(id, on) }
            )
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun OfflineBanner(text: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RsShape12).background(RsColors.Warn.copy(alpha = 0.14f))
            .border(1.dp, RsColors.Warn, RsShape12).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Txt(text, modifier = Modifier.weight(1f), color = RsColors.Text, size = 14)
        TextAction(Strings.RETRY, RsColors.Warn, true, onRetry)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun CountryRow(model: ExperiencesModel) {
    if (model.countries.size < 2) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (info in model.countries) {
            Chip(info.name, info.code == model.country, Modifier.widthIn(min = 64.dp)) { model.selectCountry(info.code) }
        }
    }
}

// ---- filters

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun FiltersCard(model: ExperiencesModel) {
    val f = model.filters
    val open = model.filtersOpen
    Card {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { model.toggleFilters() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(
                Strings.filtersSet(ExperiencesLogic.activeCount(f)), modifier = Modifier.weight(1f),
                size = 18, weight = FontWeight.Bold
            )
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    drawGlyph(if (open) Glyph.CHEVRON_UP else Glyph.CHEVRON_DOWN, RsColors.Accent)
                }
            }
        }
        if (open) {
            RangeFilter(
                Strings.FILTER_DISTANCE, ExperiencesLogic.kmRangeText(f),
                f.minKm, f.maxKm, ExperienceFilters.DEFAULT_MAX_KM, ExperiencesLogic.KM_STEP
            ) { lo, hi -> model.changeFilters(f.copy(minKm = lo, maxKm = hi)) }
            RangeFilter(
                Strings.FILTER_DURATION, ExperiencesLogic.minutesRangeText(f),
                f.minMinutes, f.maxMinutes, ExperienceFilters.DEFAULT_MAX_MINUTES, ExperiencesLogic.MINUTES_STEP
            ) { lo, hi -> model.changeFilters(f.copy(minMinutes = lo, maxMinutes = hi)) }
            for (axis in TargetAxis.values()) {
                TargetFilter(axis, axis.read(f), { model.changeFilters(axis.write(f, it)) }, { model.changeFilters(axis.toggleAny(f)) })
            }
            if (model.regions.size > 1) {
                val options = ExperiencesLogic.regionOptions(model.regions)
                val index = options.indexOfFirst { it.equals(f.region, ignoreCase = true) }.let { if (it < 0) 0 else it }
                StepperRow(
                    Strings.FILTER_REGION, ExperiencesLogic.regionText(f.region),
                    index > 0, index < options.lastIndex,
                    { model.changeFilters(f.copy(region = ExperiencesLogic.stepRegion(options, f.region, -1))) },
                    { model.changeFilters(f.copy(region = ExperiencesLogic.stepRegion(options, f.region, 1))) }
                )
            }
            StepperRow(
                Strings.FILTER_DETOURS, ExperiencesLogic.detoursText(f.detours),
                f.detours > 0, f.detours < ExperienceFilters.MAX_DETOURS,
                { model.changeFilters(f.copy(detours = f.detours - 1)) },
                { model.changeFilters(f.copy(detours = f.detours + 1)) }
            )
            TextAction(Strings.FILTERS_RESET, RsColors.Accent, !ExperiencesLogic.isDefault(f)) { model.resetFilters() }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RangeFilter(title: String, readout: String, low: Int, high: Int, max: Int, step: Int, onChange: (Int, Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, modifier = Modifier.weight(1f), color = RsColors.Text2, size = 15)
            Txt(readout, color = RsColors.Accent, size = 15, weight = FontWeight.Bold)
        }
        RsRangeSlider(low, high, 0, max, step, onChange)
    }
}

/** A "target" slider. Without a value it says so and its thumb is gone; touching the track gives it one. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun TargetFilter(axis: TargetAxis, value: Int?, onValue: (Int?) -> Unit, onToggleAny: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(
                if (value == null) Strings.FILTER_ANY_NOTE else Strings.targetValue(axis.highLabel(), value),
                modifier = Modifier.weight(1f), color = if (value == null) RsColors.Dim else RsColors.Accent,
                size = 15, weight = FontWeight.Bold
            )
            Chip(Strings.FILTER_ANY, value == null, Modifier.width(72.dp), onToggleAny)
        }
        RsSlider(value, 0, 100, 5) { onValue(it) }
        Row(Modifier.fillMaxWidth()) {
            Txt(axis.lowLabel(), modifier = Modifier.weight(1f), color = RsColors.Dim, size = 13)
            Txt(axis.highLabel(), color = RsColors.Dim, size = 13)
        }
    }
}

private const val SLIDER_HEIGHT_DP = 44
private const val SLIDER_PAD_DP = 14f
private const val THUMB_DP = 11f

/** One thumb on a track from [min] to [max]; null [value] is "any": no thumb, a dim track. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RsSlider(value: Int?, min: Int, max: Int, step: Int, onValue: (Int) -> Unit) {
    val change by rememberUpdatedState(onValue)
    Canvas(
        Modifier.fillMaxWidth().height(SLIDER_HEIGHT_DP.dp)
            .pointerInput(Triple(min, max, step)) {
                val pad = SLIDER_PAD_DP.dp.toPx()
                detectTapGestures { p ->
                    change(SliderMath.valueOf(ChartMath.fractionOfX(p.x, size.width.toFloat(), pad, pad), min, max, step))
                }
            }
            .pointerInput(Triple(min, max, step)) {
                val pad = SLIDER_PAD_DP.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { p -> change(SliderMath.valueOf(ChartMath.fractionOfX(p.x, size.width.toFloat(), pad, pad), min, max, step)) },
                    onDragEnd = { },
                    onDragCancel = { },
                    onHorizontalDrag = { c, _ -> change(SliderMath.valueOf(ChartMath.fractionOfX(c.position.x, size.width.toFloat(), pad, pad), min, max, step)) }
                )
            }
    ) {
        val pad = SLIDER_PAD_DP * density
        val y = size.height / 2f
        val w = size.width
        val track = 4f * density
        drawLine(RsColors.Line, Offset(pad, y), Offset(w - pad, y), track, StrokeCap.Round)
        if (value != null) {
            val x = ChartMath.xOfFraction(SliderMath.fractionOf(value, min, max), w, pad, pad)
            drawLine(RsColors.Accent, Offset(pad, y), Offset(x, y), track, StrokeCap.Round)
            drawCircle(RsColors.Accent, THUMB_DP * density, Offset(x, y))
            drawCircle(RsColors.AccentInk, THUMB_DP * density, Offset(x, y), style = Stroke(2f * density))
        }
    }
}

/** Two thumbs on one track; a touch takes the nearer one, and one cannot pass the other. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun RsRangeSlider(low: Int, high: Int, min: Int, max: Int, step: Int, onChange: (Int, Int) -> Unit) {
    val change by rememberUpdatedState(onChange)
    val lowNow by rememberUpdatedState(low)
    val highNow by rememberUpdatedState(high)
    val thumb = remember { IntArray(1) }
    Canvas(
        Modifier.fillMaxWidth().height(SLIDER_HEIGHT_DP.dp)
            .pointerInput(Triple(min, max, step)) {
                val pad = SLIDER_PAD_DP.dp.toPx()
                detectTapGestures { p ->
                    val f = ChartMath.fractionOfX(p.x, size.width.toFloat(), pad, pad)
                    val which = SliderMath.nearestThumb(f, SliderMath.fractionOf(lowNow, min, max), SliderMath.fractionOf(highNow, min, max))
                    val moved = SliderMath.moveThumb(which, SliderMath.valueOf(f, min, max, step), lowNow, highNow)
                    change(moved.first, moved.second)
                }
            }
            .pointerInput(Triple(min, max, step)) {
                val pad = SLIDER_PAD_DP.dp.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { p ->
                        val f = ChartMath.fractionOfX(p.x, size.width.toFloat(), pad, pad)
                        thumb[0] = SliderMath.nearestThumb(f, SliderMath.fractionOf(lowNow, min, max), SliderMath.fractionOf(highNow, min, max))
                        val moved = SliderMath.moveThumb(thumb[0], SliderMath.valueOf(f, min, max, step), lowNow, highNow)
                        change(moved.first, moved.second)
                    },
                    onDragEnd = { },
                    onDragCancel = { },
                    onHorizontalDrag = { c, _ ->
                        val f = ChartMath.fractionOfX(c.position.x, size.width.toFloat(), pad, pad)
                        val moved = SliderMath.moveThumb(thumb[0], SliderMath.valueOf(f, min, max, step), lowNow, highNow)
                        change(moved.first, moved.second)
                    }
                )
            }
    ) {
        val pad = SLIDER_PAD_DP * density
        val y = size.height / 2f
        val w = size.width
        val track = 4f * density
        val xLow = ChartMath.xOfFraction(SliderMath.fractionOf(low, min, max), w, pad, pad)
        val xHigh = ChartMath.xOfFraction(SliderMath.fractionOf(high, min, max), w, pad, pad)
        drawLine(RsColors.Line, Offset(pad, y), Offset(w - pad, y), track, StrokeCap.Round)
        drawLine(RsColors.Accent, Offset(xLow, y), Offset(xHigh, y), track, StrokeCap.Round)
        for (x in floatArrayOf(xLow, xHigh)) {
            drawCircle(RsColors.Accent, THUMB_DP * density, Offset(x, y))
            drawCircle(RsColors.AccentInk, THUMB_DP * density, Offset(x, y), style = Stroke(2f * density))
        }
    }
}

// ---- cards

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ExperienceCard(
    view: CardView,
    enabled: Boolean,
    onOpen: () -> Unit,
    onOtherIdeas: () -> Unit,
    onVisible: (String, Boolean) -> Unit
) {
    // A lazy list only composes what is on screen and a little beyond: being composed is being visible.
    // The id is captured here: the callback reports the card this effect was made for, never the one
    // the slot shows by the time it is disposed.
    val seen by rememberUpdatedState(onVisible)
    val id = view.id
    DisposableEffect(id) {
        seen(id, true)
        onDispose { seen(id, false) }
    }
    Card(Modifier.clip(RsShape16).clickable(enabled = enabled, onClick = onOpen)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(view.name, size = 18, weight = FontWeight.Bold, maxLines = 2)
                Txt(view.region, color = RsColors.Dim, size = 14, maxLines = 1)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Txt(view.kmText, size = 17, weight = FontWeight.Bold, maxLines = 1)
                    Txt(view.durationText, size = 17, weight = FontWeight.Bold, maxLines = 1)
                }
                view.elevationText?.let { Txt(it, color = RsColors.Text2, size = 14, maxLines = 1) }
            }
            ShapeThumb(view.shape)
        }
        if (view.tags.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in view.tags) TagChip(tag)
            }
        }
        view.mismatchLine?.let { Txt(it, color = RsColors.Dim, size = 13, maxLines = 1) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextAction(Strings.OTHER_IDEAS, RsColors.Accent, enabled && view.canOtherIdeas, onOtherIdeas)
            view.ideaText?.let { Txt(it, color = RsColors.Dim, size = 13, modifier = Modifier.padding(start = 4.dp)) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (view.loading) BusyMark()
                else if (view.detoursUnavailable) Txt(Strings.DETOURS_UNAVAILABLE, color = RsColors.Dim, size = 12, maxLines = 1)
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun TagChip(text: String) {
    Box(
        Modifier.clip(RsShape8).background(RsColors.Surface2).border(1.dp, RsColors.Line, RsShape8)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Txt(text, color = RsColors.Text2, size = 12, weight = FontWeight.Bold, maxLines = 1)
    }
}

/** The small, quiet sign that a card's route is being planned: a dot that breathes, and what it is doing. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun BusyMark() {
    var bright by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BUSY_BLINK_MS)
            bright = !bright
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(8.dp)) {
            drawCircle(RsColors.Accent.copy(alpha = if (bright) 0.95f else 0.3f), size.minDimension / 2f)
        }
        Txt(Strings.FINDING_DETOURS, color = RsColors.Dim, size = 12, maxLines = 1)
    }
}

private const val BUSY_BLINK_MS = 600L

/** The ride's silhouette, north up and not stretched, with a green dot at the start and a red one at the end. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ShapeThumb(shape: List<Pair<Double, Double>>) {
    Box(Modifier.size(width = 104.dp, height = 84.dp).clip(RsShape12).background(RsColors.Surface2)) {
        Canvas(Modifier.fillMaxSize()) {
            val points = ShapeMath.project(shape, size.width, size.height, 12f * density)
            if (points.size < 2) return@Canvas
            val path = Path()
            path.moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) path.lineTo(points[i].first, points[i].second)
            drawPath(path, RsColors.Blue, style = Stroke(3f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val start = points[0]
            val end = points[points.size - 1]
            drawCircle(Color(MapColors.START), 5f * density, Offset(start.first, start.second))
            drawCircle(Color(MapColors.FINISH), 5f * density, Offset(end.first, end.second))
        }
    }
}
