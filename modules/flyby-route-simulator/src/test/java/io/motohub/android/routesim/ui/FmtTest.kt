// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import io.motohub.android.module.ModuleRouteError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FmtTest {

    @Test
    fun printsDistancesAndDurations() {
        assertEquals("12.3 km", Fmt.distance(12345.0))
        assertEquals("250 km", Fmt.distance(250_000.0))
        assertEquals("1h 02m", Fmt.duration(3725.0))
        assertEquals("1 min", Fmt.duration(90.0))
        assertEquals("1 min", Fmt.duration(10.0))
        assertEquals("12h 00m", Fmt.duration(43_200.0))
    }

    @Test
    fun printsTheCursorsClock() {
        assertEquals("0:00", Fmt.clock(0f))
        assertEquals("1:05", Fmt.clock(65f))
        assertEquals("1:02:05", Fmt.clock(3725f))
        assertEquals("0:00", Fmt.clock(-3f))
    }

    @Test
    fun printsSpeedsAndCoordinates() {
        assertEquals("62 km/h", Fmt.speed(61.6f))
        assertEquals("–", Fmt.speed(Float.NaN))
        assertEquals("45.07030, 7.68690", Fmt.coordinates(45.0703, 7.6869))
    }

    @Test
    fun shortensPlaceLabels() {
        assertEquals("Torino", Fmt.shortLabel("Torino, Piemonte, Italia"))
        assertEquals("Sestriere", Fmt.shortLabel("  Sestriere "))
        val long = Fmt.shortLabel("a".repeat(40))
        assertEquals(28, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun namesAPlanFromItsEnds() {
        assertEquals("New plan", Strings.defaultPlanName(emptyList()))
        assertEquals("Torino", Strings.defaultPlanName(listOf("Torino")))
        assertEquals("Torino → Sestriere", Strings.defaultPlanName(listOf("Torino", "Pinerolo", "Sestriere")))
    }

    @Test
    fun labelsThePinsAlongTheRoute() {
        assertEquals("A", Strings.pinLabel(0, 4))
        assertEquals("1", Strings.pinLabel(1, 4))
        assertEquals("2", Strings.pinLabel(2, 4))
        assertEquals("B", Strings.pinLabel(3, 4))
        assertEquals("Start", Strings.stopRole(0, 4))
        assertEquals("Via 2", Strings.stopRole(2, 4))
        assertEquals("Finish", Strings.stopRole(3, 4))
    }

    @Test
    fun keysChangeWhenAStopMoves() {
        val a = coordinatesKey(doubleArrayOf(45.0, 46.0), doubleArrayOf(7.0, 8.0))
        assertEquals("45.000000,7.000000;46.000000,8.000000;", a)
        assertTrue(a != coordinatesKey(doubleArrayOf(45.0, 46.0), doubleArrayOf(7.0, 8.000001)))
        assertEquals(a, coordinatesKey(doubleArrayOf(45.0, 46.0), doubleArrayOf(7.0, 8.0)))
    }

    @Test
    fun thinsLongRoutesKeepingBothEnds() {
        assertEquals(0, thinIndices(0, 5).size)
        assertEquals(listOf(0), thinIndices(1, 5).toList())
        assertEquals(listOf(0, 1, 2, 3, 4), thinIndices(5, 10).toList())
        val thin = thinIndices(10_000, 5)
        assertEquals(5, thin.size)
        assertEquals(0, thin[0])
        assertEquals(9_999, thin[4])
        for (i in 1 until thin.size) assertTrue(thin[i] > thin[i - 1])
        assertEquals(listOf(0, 9), thinIndices(10, 1).toList())
    }

    @Test
    fun summarisesARide() {
        val stats = rideStats(longArrayOf(0, 1000, 2000), floatArrayOf(10f, 20f, Float.NaN), 100.0)
        assertEquals(2.0, stats.durationSeconds, 1e-9)
        assertEquals(180f, stats.averageKph, 1e-3f)
        assertEquals(20f, stats.topKph, 0f)
        val empty = rideStats(longArrayOf(), floatArrayOf(), 0.0)
        assertEquals(0f, empty.averageKph, 0f)
    }

    @Test
    fun readsOutTheChartsValues() {
        assertEquals("62 km/h", chartReadout(ChartKind.SPEED, 61.6f))
        assertEquals("1234 m", chartReadout(ChartKind.ALTITUDE, 1234.2f))
        assertEquals("12° left", chartReadout(ChartKind.LEAN, -12.4f))
        assertEquals("30° right", chartReadout(ChartKind.LEAN, 30f))
        assertEquals("0°", chartReadout(ChartKind.LEAN, 0.2f))
        assertEquals("4800 rpm", chartReadout(ChartKind.RPM, 4800f))
        assertEquals("–", chartReadout(ChartKind.RPM, Float.NaN))
        assertEquals("1200", axisLabel(1200f))
    }

    @Test
    fun tellsTheRiderWhyPlanningFailed() {
        assertEquals(Strings.OFFLINE, failureText(ModuleRouteError.NO_NETWORK, "socket closed"))
        assertTrue(failureText(ModuleRouteError.TOO_LONG, null).contains("500"))
        assertEquals("Too far", failureText(ModuleRouteError.TOO_LONG, "Too far"))
        assertEquals("boom", failureText(ModuleRouteError.OTHER, "boom"))
        assertEquals(Strings.GENERIC_FAILURE, failureText(ModuleRouteError.OTHER, null))
        assertEquals(Strings.GENERIC_FAILURE, failureText(ModuleRouteError.OTHER, "  "))
        assertEquals(Strings.RATE_LIMITED, failureText(ModuleRouteError.RATE_LIMITED, null))
        assertEquals(Strings.NO_API_KEY, failureText(ModuleRouteError.NO_API_KEY, "x"))
    }
}
