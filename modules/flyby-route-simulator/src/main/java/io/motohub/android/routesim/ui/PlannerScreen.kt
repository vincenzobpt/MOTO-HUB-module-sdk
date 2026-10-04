// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The planner: the lent map on top, the stops, the day and the style under it, the actions at the foot.
package io.motohub.android.routesim.ui

import android.view.WindowInsets
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.module.ModuleMap
import io.motohub.android.module.ModuleMapListener
import io.motohub.android.module.ModuleUi
import io.motohub.android.routesim.core.Stop
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.TrafficLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** The bar at the top of the module's own screens: a way back, the title, and what else is on offer. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun TopBar(title: String, onBack: () -> Unit, trailing: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconBox(Glyph.BACK, 44.dp, true, RsColors.Text, onBack)
        Txt(title, modifier = Modifier.weight(1f), size = 20, weight = FontWeight.Bold, maxLines = 1)
        trailing()
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
internal fun PlannerScreen(
    env: RsEnv,
    model: PlannerModel,
    onPreview: () -> Unit,
    onPlans: () -> Unit,
    onCalibrate: () -> Unit,
    onBack: () -> Unit
) {
    // Back from the calibration page: the profile follows what was learned meanwhile.
    LaunchedEffect(env.learned) { model.syncLearned() }
    var bigMap by remember { mutableStateOf(false) }
    val map: ModuleMap? = remember { env.openMap() }

    // The map is the app's, lent for as long as this screen is up; its touches become stops.
    DisposableEffect(map) {
        map?.setListener(object : ModuleMapListener {
            override fun onTap(latitude: Double, longitude: Double) {
                model.select(-1)
            }

            override fun onLongPress(latitude: Double, longitude: Double) {
                model.addStopAt(latitude, longitude)
            }

            override fun onPinTap(index: Int) {
                model.select(index)
            }
        })
        onDispose {
            if (map != null) env.closeMap(map)
        }
    }

    val route = model.routeToShow()
    LaunchedEffect(map, model.stops, model.selected) {
        map?.setPins(MapScenes.plannerPins(model.stops, model.selected))
    }
    LaunchedEffect(map, model.stops, route) {
        map?.setLines(MapScenes.plannerLines(model.stops, route))
    }
    LaunchedEffect(map, model.fitVersion) {
        if (map == null) return@LaunchedEffect
        val stops = model.stops
        if (stops.isEmpty()) return@LaunchedEffect
        val shown = model.routeToShow()
        if (stops.size == 1 && shown == null) {
            map.center(stops[0].latitude, stops[0].longitude, 12.0)
        } else {
            map.fit(MapScenes.plannerFitLats(stops, shown), MapScenes.plannerFitLons(stops, shown))
        }
    }

    val mapShare = if (bigMap) 0.62f else 0.34f
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(Strings.PLANNER_TITLE, onBack) {
            TextAction(Strings.PLANS_BUTTON, RsColors.Accent, true, onPlans)
        }
        Box(Modifier.fillMaxWidth().weight(mapShare).background(RsColors.Surface)) {
            if (map != null) {
                Box(Modifier.fillMaxSize()) { map.Surface() }
            }
            if (model.stops.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
                    Txt(
                        Strings.MAP_HINT,
                        modifier = Modifier.clip(RsShape12).background(RsColors.Bg.copy(alpha = 0.82f)).padding(10.dp),
                        size = 14, color = RsColors.Text
                    )
                }
            }
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopEnd) {
                Txt(
                    if (bigMap) Strings.MAP_SMALLER else Strings.MAP_BIGGER,
                    modifier = Modifier.clip(RsShape12).background(RsColors.Bg.copy(alpha = 0.82f))
                        .clickable { bigMap = !bigMap }.padding(horizontal = 12.dp, vertical = 12.dp),
                    size = 14, weight = FontWeight.Bold, color = RsColors.Accent
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f - mapShare).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StopsCard(model)
            SearchCard(env, model)
            WhenCard(model, env.zone)
            StyleCard(env, model, onCalibrate)
            TrafficCard(model)
            AdvancedCard(env, model)
            BigButton(Strings.SAVE_PLAN, Modifier.fillMaxWidth(), model.stops.isNotEmpty(), false) { model.savePlan() }
        }
        ActionBar(env, model, onPreview)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun StopsCard(model: PlannerModel) {
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(Strings.STOPS_TITLE, modifier = Modifier.weight(1f), size = 18, weight = FontWeight.Bold)
            if (model.stops.isNotEmpty()) {
                TextAction(Strings.CLEAR_ALL, RsColors.Bad, true) { model.clearStops() }
            }
        }
        model.titleOverride?.let { Txt(Strings.rideName(it), color = RsColors.Text2, size = 14, maxLines = 2) }
        if (model.stops.isEmpty()) {
            Txt(Strings.STOPS_EMPTY, color = RsColors.Dim, size = 15)
        } else {
            val count = model.stops.size
            for (i in 0 until count) {
                StopRow(model, i, count)
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun StopRow(model: PlannerModel, index: Int, count: Int) {
    if (index >= model.stops.size) return
    val stop = model.stops[index]
    val selected = model.selected == index
    val pinColor = when {
        selected -> MapColors.SELECTED
        index == 0 -> MapColors.START
        index == count - 1 -> MapColors.FINISH
        else -> MapColors.VIA
    }
    Row(
        Modifier.fillMaxWidth().clip(RsShape12)
            .background(if (selected) RsColors.Accent.copy(alpha = 0.16f) else RsColors.Surface2)
            .border(if (selected) 2.dp else 1.dp, if (selected) RsColors.Accent else RsColors.Line, RsShape12)
            .clickable { model.select(index) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(pinColor)), contentAlignment = Alignment.Center) {
            Txt(Strings.pinLabel(index, count), color = Color(0xFF0B0F14), size = 15, weight = FontWeight.Bold, maxLines = 1)
        }
        Column(Modifier.weight(1f)) {
            Txt(Strings.stopRole(index, count), color = RsColors.Dim, size = 12, maxLines = 1)
            Txt(stop.label, size = 15, maxLines = 2)
        }
        IconBox(Glyph.UP, 42.dp, index > 0, RsColors.Text) { model.move(index, -1) }
        IconBox(Glyph.DOWN, 42.dp, index < count - 1, RsColors.Text) { model.move(index, 1) }
        IconBox(Glyph.CLOSE, 42.dp, true, RsColors.Bad) { model.removeAt(index) }
    }
}

private sealed class SearchState {
    object Idle : SearchState()
    object TooShort : SearchState()
    object Busy : SearchState()
    object Empty : SearchState()
    class Coords(val point: LatLon) : SearchState()
    class Found(val labels: List<String>, val latitudes: DoubleArray, val longitudes: DoubleArray) : SearchState()
    class Failed(val text: String) : SearchState()
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun SearchCard(env: RsEnv, model: PlannerModel) {
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    val view = LocalView.current

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            state = SearchState.Idle
            return@LaunchedEffect
        }
        val point = SearchInput.parseLatLon(q)
        if (point != null) {
            state = SearchState.Coords(point)
            return@LaunchedEffect
        }
        if (!SearchInput.isSearchable(q)) {
            state = SearchState.TooShort
            return@LaunchedEffect
        }
        state = SearchState.Idle
        delay(SearchInput.DEBOUNCE_MS)
        state = SearchState.Busy
        val stops = model.stops
        val nearLat = if (stops.isEmpty()) Double.NaN else stops[stops.size - 1].latitude
        val nearLon = if (stops.isEmpty()) Double.NaN else stops[stops.size - 1].longitude
        val result = withContext(Dispatchers.Default) {
            try {
                env.host.places.search(q, nearLat, nearLon, 6)
            } catch (_: Throwable) {
                null
            }
        }
        if (result == null) {
            state = SearchState.Failed(Strings.searchFailed(null))
        } else if (!result.ok) {
            state = SearchState.Failed(Strings.searchFailed(result.error))
        } else if (result.labels.isEmpty()) {
            state = SearchState.Empty
        } else {
            val labels = ArrayList<String>()
            for (l in result.labels) labels.add(l)
            state = SearchState.Found(labels, result.latitudes, result.longitudes)
        }
    }

    val done: () -> Unit = {
        query = ""
        state = SearchState.Idle
        view.windowInsetsController?.hide(WindowInsets.Type.ime())
    }

    Card {
        Txt(Strings.SEARCH_TITLE, size = 18, weight = FontWeight.Bold)
        RsField(query, { query = it }, Strings.SEARCH_HINT, Modifier.fillMaxWidth(), false)
        when (val s = state) {
            SearchState.Idle -> {}
            SearchState.TooShort -> Txt(Strings.SEARCH_TOO_SHORT, color = RsColors.Dim, size = 14)
            SearchState.Busy -> Txt(Strings.SEARCHING, color = RsColors.Accent, size = 15)
            SearchState.Empty -> Txt(Strings.NO_RESULTS, color = RsColors.Dim, size = 15)
            is SearchState.Failed -> Txt(s.text, color = RsColors.Bad, size = 15)
            is SearchState.Coords -> ResultRow(Strings.useCoordinates(s.point.lat, s.point.lon)) {
                model.addStopAt(s.point.lat, s.point.lon)
                done()
            }
            is SearchState.Found -> {
                for (i in s.labels.indices) {
                    ResultRow(s.labels[i]) {
                        model.addStop(Stop(s.latitudes[i], s.longitudes[i], s.labels[i]))
                        done()
                    }
                }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ResultRow(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RsShape12).background(RsColors.Surface2)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Txt(text, size = 15, maxLines = 2)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun WhenCard(model: PlannerModel, zone: ZoneId) {
    val millis = model.settings.startAtMillis
    Card {
        Txt(Strings.WHEN_TITLE, size = 18, weight = FontWeight.Bold)
        EditableStepper(
            Strings.DATE_LABEL, Strings.DATE_HINT, millis,
            { WhenMath.formatDate(it, zone) },
            { text -> WhenMath.parseDate(text, millis, zone) },
            { model.setStart(WhenMath.shiftDays(model.settings.startAtMillis, zone, -1)) },
            { model.setStart(WhenMath.shiftDays(model.settings.startAtMillis, zone, 1)) },
            { model.setStart(it) }
        )
        EditableStepper(
            Strings.TIME_LABEL, Strings.TIME_HINT, millis,
            { WhenMath.formatTime(it, zone) },
            { text -> WhenMath.parseTime(text, millis, zone) },
            { model.setStart(WhenMath.shiftMinutes(model.settings.startAtMillis, zone, -15)) },
            { model.setStart(WhenMath.shiftMinutes(model.settings.startAtMillis, zone, 15)) },
            { model.setStart(it) }
        )
        Txt(Strings.WHEN_NOTE, color = RsColors.Dim, size = 13)
    }
}

/**
 * A value that can be typed or stepped. The text is the rider's until it parses; when the value
 * moves some other way (a step) the text follows it.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun EditableStepper(
    label: String,
    hint: String,
    millis: Long,
    format: (Long) -> String,
    parse: (String) -> Long?,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onValue: (Long) -> Unit
) {
    var text by remember { mutableStateOf(format(millis)) }
    LaunchedEffect(millis) {
        if (parse(text) != millis) text = format(millis)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, color = RsColors.Text2, size = 14)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepeatBox(Glyph.MINUS, true, onMinus)
            RsField(
                text,
                { typed ->
                    text = typed
                    val parsed = parse(typed)
                    if (parsed != null) onValue(parsed)
                },
                hint, Modifier.weight(1f), parse(text) == null
            )
            RepeatBox(Glyph.PLUS, true, onPlus)
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun StyleCard(env: RsEnv, model: PlannerModel, onCalibrate: () -> Unit) {
    val style = model.settings.style
    val tuned = StyleBase.isTuned(env.learned)
    Card {
        Txt(Strings.STYLE_TITLE, size = 18, weight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(
                StyleBase.indicator(env.learned), modifier = Modifier.weight(1f),
                color = if (tuned) RsColors.Ok else RsColors.Dim, size = 15, weight = FontWeight.Bold
            )
            TextAction(Strings.CALIBRATE_LINK, RsColors.Accent, true, onCalibrate)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in RideStyle.values()) {
                Chip(Strings.style(s), style == s, Modifier.weight(1f)) { model.setStyle(s) }
            }
        }
        Txt(Strings.styleNote(style), color = RsColors.Dim, size = 14)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun TrafficCard(model: PlannerModel) {
    val level = model.settings.traffic
    Card {
        Txt(Strings.TRAFFIC_TITLE, size = 18, weight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in TrafficLevel.values()) {
                Chip(Strings.traffic(t), level == t, Modifier.weight(1f)) { model.setTraffic(t) }
            }
        }
        Txt(Strings.trafficNote(level), color = RsColors.Dim, size = 14)
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun AdvancedCard(env: RsEnv, model: PlannerModel) {
    var open by remember { mutableStateOf(false) }
    val profile = model.settings.profile
    Card {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(Strings.ADVANCED_TITLE, modifier = Modifier.weight(1f), size = 18, weight = FontWeight.Bold)
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(22.dp)) {
                    drawGlyph(if (open) Glyph.CHEVRON_UP else Glyph.CHEVRON_DOWN, RsColors.Accent)
                }
            }
        }
        if (open) {
            Txt(Strings.ADVANCED_NOTE, color = RsColors.Dim, size = 13)
            for (param in AdvancedParam.values()) {
                StepperRow(
                    Strings.param(param), param.format(profile),
                    param.canNudge(profile, -1), param.canNudge(profile, 1),
                    { model.nudgeParam(param, -1) }, { model.nudgeParam(param, 1) }
                )
            }
            if (!AdvancedParam.isUntouched(profile, StyleBase.profile(model.settings.style, env.learned))) {
                TextAction(Strings.ADVANCED_RESET, RsColors.Accent, true) { model.resetProfile() }
            }
        }
    }
}

@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun ActionBar(env: RsEnv, model: PlannerModel, onPreview: () -> Unit) {
    val flyby = env.flybyInstalled
    val saved = model.savedRide
    Column(
        Modifier.fillMaxWidth().background(RsColors.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (model.busy) {
                Txt(model.progress, modifier = Modifier.weight(1f), color = RsColors.Accent, size = 15, weight = FontWeight.Bold, maxLines = 2)
                TextAction(Strings.CANCEL, RsColors.Text2, true) { model.cancelRun() }
            } else if (model.message.isNotEmpty()) {
                Txt(
                    model.message, modifier = Modifier.weight(1f),
                    color = if (model.messageIsError) RsColors.Bad else RsColors.Ok, size = 14, maxLines = 3
                )
                if (saved != null && flyby == true) {
                    TextAction(Strings.OPEN_IN_FLYBY, RsColors.Accent, true) { model.openSavedInFlyby() }
                }
            } else if (model.stops.size < 2) {
                Txt(Strings.NEED_TWO_STOPS, color = RsColors.Dim, size = 14)
            } else if (flyby == false) {
                Txt(Strings.FLYBY_MISSING, color = RsColors.Dim, size = 13, maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(Strings.PREVIEW, Modifier.weight(1f), model.canRun(), false) { model.run(RunMode.PREVIEW, onPreview) }
            BigButton(Strings.GENERATE, Modifier.weight(1f), model.canRun(), true) { model.run(RunMode.SAVE) { } }
        }
        BigButton(Strings.GENERATE_AND_OPEN, Modifier.fillMaxWidth(), model.canRun() && flyby == true, false) {
            model.run(RunMode.SAVE_AND_OPEN) { }
        }
    }
}
