// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// What the screens put on the lent map: the planner's pins and route, the preview's coloured line.
package io.motohub.android.routesim.ui

import io.motohub.android.module.ModuleMapLine
import io.motohub.android.module.ModuleMapPin
import io.motohub.android.routesim.core.PreparedRoute
import io.motohub.android.routesim.core.PreviewSeries
import io.motohub.android.routesim.core.Stop

internal object MapColors {
    val START = 0xFF2FBF71.toInt()
    val VIA = 0xFF4C9BFF.toInt()
    val FINISH = 0xFFFF5A4D.toInt()
    val SELECTED = 0xFFFFB23F.toInt()
    val ROUTE = 0xFF3D8BFF.toInt()
    val GUIDE = 0xFFB4C0CC.toInt()
    val CURSOR = 0xFFFFFFFF.toInt()
}

internal object MapScenes {

    fun plannerPins(stops: List<Stop>, selected: Int): Array<ModuleMapPin> {
        val count = stops.size
        return Array(count) { i ->
            val color = when {
                i == selected -> MapColors.SELECTED
                i == 0 -> MapColors.START
                i == count - 1 -> MapColors.FINISH
                else -> MapColors.VIA
            }
            ModuleMapPin(stops[i].latitude, stops[i].longitude, color, Strings.pinLabel(i, count))
        }
    }

    /** The planned route when there is one, else a dashed guide through the stops. */
    fun plannerLines(stops: List<Stop>, route: PreparedRoute?): Array<ModuleMapLine> {
        if (route != null) {
            val lats = route.route.lats
            val lons = route.route.lons
            val keep = thinIndices(lats.size, MAP_LINE_POINTS)
            val la = DoubleArray(keep.size) { lats[keep[it]] }
            val lo = DoubleArray(keep.size) { lons[keep[it]] }
            return arrayOf(ModuleMapLine(la, lo, MapColors.ROUTE, 5f, false))
        }
        if (stops.size < 2) return arrayOf<ModuleMapLine>()
        val la = DoubleArray(stops.size) { stops[it].latitude }
        val lo = DoubleArray(stops.size) { stops[it].longitude }
        return arrayOf(ModuleMapLine(la, lo, MapColors.GUIDE, 3f, true))
    }

    /** The points the map frames for the planner: the route if planned, else the stops. */
    fun plannerFitLats(stops: List<Stop>, route: PreparedRoute?): DoubleArray {
        if (route != null) {
            val lats = route.route.lats
            val keep = thinIndices(lats.size, 400)
            return DoubleArray(keep.size) { lats[keep[it]] }
        }
        return DoubleArray(stops.size) { stops[it].latitude }
    }

    fun plannerFitLons(stops: List<Stop>, route: PreparedRoute?): DoubleArray {
        if (route != null) {
            val lons = route.route.lons
            val keep = thinIndices(lons.size, 400)
            return DoubleArray(keep.size) { lons[keep[it]] }
        }
        return DoubleArray(stops.size) { stops[it].longitude }
    }

    /** The ride's line in a handful of runs coloured by speed. */
    fun previewLines(series: PreviewSeries, topKph: Float): Array<ModuleMapLine> {
        val runs = SpeedRamp.runs(series.speedKph, topKph, SPEED_RUNS)
        return Array(runs.size) { i ->
            val run = runs[i]
            ModuleMapLine(
                java.util.Arrays.copyOfRange(series.latitudes, run.first, run.last + 1),
                java.util.Arrays.copyOfRange(series.longitudes, run.first, run.last + 1),
                SpeedRamp.colorOf(run.bucket), 6f, false
            )
        }
    }

    /** Start, finish and, when there is a cursor, the point it stands on. */
    fun previewPins(series: PreviewSeries, cursorIndex: Int): Array<ModuleMapPin> {
        val n = series.latitudes.size
        if (n == 0) return arrayOf<ModuleMapPin>()
        val pins = ArrayList<ModuleMapPin>()
        pins.add(ModuleMapPin(series.latitudes[0], series.longitudes[0], MapColors.START, "A"))
        pins.add(ModuleMapPin(series.latitudes[n - 1], series.longitudes[n - 1], MapColors.FINISH, "B"))
        if (cursorIndex in 0 until n) {
            val speed = if (cursorIndex < series.speedKph.size) series.speedKph[cursorIndex] else Float.NaN
            pins.add(ModuleMapPin(series.latitudes[cursorIndex], series.longitudes[cursorIndex], MapColors.CURSOR, Fmt.speed(speed)))
        }
        return Array(pins.size) { pins[it] }
    }
}
