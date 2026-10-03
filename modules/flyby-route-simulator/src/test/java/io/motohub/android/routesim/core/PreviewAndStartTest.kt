// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.SimOutput
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAndStartTest {

    private fun output(n: Int) = SimOutput(
        timesMillis = LongArray(n) { it * 100L },
        latitudes = DoubleArray(n) { 45.0 + it * 1e-5 },
        longitudes = DoubleArray(n) { 7.0 + it * 2e-5 },
        altitudesMeters = DoubleArray(n) { 100.0 + it },
        speedsKph = FloatArray(n) { it.toFloat() },
        headingDegrees = FloatArray(n),
        leanDegrees = FloatArray(n) { -it.toFloat() },
        engineRpm = FloatArray(n) { if (it == 3) Float.NaN else 1000f + it },
        gear = IntArray(n),
        accuracyMeters = FloatArray(n),
        satellites = IntArray(n),
        distanceMeters = 1.0,
    )

    @Test
    fun downsamplingKeepsTheEndpoints() {
        val s = previewSeries(output(10_001), 200)
        assertEquals(200, s.timesSeconds.size)
        assertEquals(0f, s.timesSeconds.first(), 0f)
        assertEquals(1000f, s.timesSeconds.last(), 0f)
        assertEquals(0f, s.speedKph.first(), 0f)
        assertEquals(10_000f, s.speedKph.last(), 0f)
        assertEquals(45.0, s.latitudes.first(), 0.0)
        assertEquals(45.0 + 10_000 * 1e-5, s.latitudes.last(), 1e-12)
        assertEquals(7.0 + 10_000 * 2e-5, s.longitudes.last(), 1e-12)
        assertEquals(-10_000f, s.leanDeg.last(), 0f)
        assertEquals(100f, s.altitudeM.first(), 0f)
        for (i in 1 until s.timesSeconds.size) assertTrue(s.timesSeconds[i] > s.timesSeconds[i - 1])
        // All seven series stay the same length.
        for (len in listOf(s.speedKph.size, s.altitudeM.size, s.leanDeg.size, s.rpm.size, s.latitudes.size, s.longitudes.size)) {
            assertEquals(200, len)
        }
    }

    @Test
    fun aShortRideIsReturnedWhole() {
        val s = previewSeries(output(50), 200)
        assertEquals(50, s.timesSeconds.size)
        assertEquals(4.9f, s.timesSeconds.last(), 1e-4f)
        assertTrue(s.rpm[3].isNaN())
    }

    @Test
    fun degenerateInputsDoNotThrow() {
        assertEquals(0, previewSeries(output(0), 10).timesSeconds.size)
        assertEquals(1, previewSeries(output(1), 10).timesSeconds.size)
        assertEquals(2, previewSeries(output(100), 0).timesSeconds.size)
    }

    @Test
    fun defaultStartIsTenLocalToday() {
        val rome = ZoneId.of("Europe/Rome")
        for ((hour, minute) in listOf(0 to 30, 9 to 59, 10 to 0, 15 to 20, 23 to 59)) {
            val now = ZonedDateTime.of(2026, 7, 14, hour, minute, 0, 0, rome).toInstant().toEpochMilli()
            val start = java.time.Instant.ofEpochMilli(defaultStartMillis(now, rome)).atZone(rome)
            assertEquals(2026, start.year)
            assertEquals(7, start.monthValue)
            assertEquals(14, start.dayOfMonth)
            assertEquals(10, start.hour)
            assertEquals(0, start.minute)
            assertEquals(0, start.second)
        }
    }

    @Test
    fun defaultStartFollowsTheZoneNotUtc() {
        // 23:30 UTC on the 14th is already the 15th in Tokyo.
        val now = ZonedDateTime.of(2026, 7, 14, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val tokyo = ZoneId.of("Asia/Tokyo")
        val start = java.time.Instant.ofEpochMilli(defaultStartMillis(now, tokyo)).atZone(tokyo)
        assertEquals(15, start.dayOfMonth)
        assertEquals(10, start.hour)
    }
}
