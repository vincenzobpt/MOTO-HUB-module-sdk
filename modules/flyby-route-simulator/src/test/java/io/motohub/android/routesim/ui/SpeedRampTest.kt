// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRampTest {

    @Test
    fun speedsFallIntoBuckets() {
        assertEquals(0, SpeedRamp.bucketOf(0f, 100f))
        assertEquals(4, SpeedRamp.bucketOf(50f, 100f))
        assertEquals(7, SpeedRamp.bucketOf(99.9f, 100f))
        assertEquals(7, SpeedRamp.bucketOf(250f, 100f))
        assertEquals(0, SpeedRamp.bucketOf(-5f, 100f))
        assertEquals(0, SpeedRamp.bucketOf(Float.NaN, 100f))
        assertEquals(0, SpeedRamp.bucketOf(50f, 0f))
    }

    @Test
    fun theRampGoesFromBlueToRed() {
        assertEquals(0xFF3D7BFF.toInt(), SpeedRamp.colorOf(0))
        assertEquals(0xFFE5393B.toInt(), SpeedRamp.colorOf(SpeedRamp.BUCKETS - 1))
        for (b in 0 until SpeedRamp.BUCKETS) {
            assertEquals("alpha of $b", 0xFF, (SpeedRamp.colorOf(b) ushr 24) and 0xFF)
        }
        // Out of range buckets are held at the ends.
        assertEquals(SpeedRamp.colorOf(0), SpeedRamp.colorOf(-3))
        assertEquals(SpeedRamp.colorOf(SpeedRamp.BUCKETS - 1), SpeedRamp.colorOf(99))
    }

    @Test
    fun runsShareTheirJoiningPoints() {
        val speeds = floatArrayOf(10f, 10f, 10f, 90f, 90f, 90f)
        val runs = SpeedRamp.runs(speeds, 100f, 60)
        assertEquals(3, runs.size)
        assertEquals(listOf(0, 2, 0), listOf(runs[0].first, runs[0].last, runs[0].bucket))
        assertEquals(listOf(2, 3, 4), listOf(runs[1].first, runs[1].last, runs[1].bucket))
        assertEquals(listOf(3, 5, 7), listOf(runs[2].first, runs[2].last, runs[2].bucket))
    }

    @Test
    fun aLongRideStaysUnderTheRunLimit() {
        val n = 1000
        val speeds = FloatArray(n) { if (it % 2 == 0) 0f else 100f }
        assertTrue(SpeedRamp.runs(speeds, 100f, 10).size <= 10)
        val ramp = FloatArray(n) { it * 100f / (n - 1) }
        val runs = SpeedRamp.runs(ramp, 100f, 10)
        assertTrue(runs.size in 2..10)
        assertEquals(0, runs[0].first)
        assertEquals(n - 1, runs[runs.size - 1].last)
        for (i in 1 until runs.size) assertEquals(runs[i - 1].last, runs[i].first)
    }

    @Test
    fun aStraightSteadyRideIsOneRun() {
        val runs = SpeedRamp.runs(FloatArray(50) { 60f }, 120f, 60)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].first)
        assertEquals(49, runs[0].last)
    }

    @Test
    fun tooLittleToDrawIsNoRuns() {
        assertTrue(SpeedRamp.runs(floatArrayOf(), 100f, 10).isEmpty())
        assertTrue(SpeedRamp.runs(floatArrayOf(5f), 100f, 10).isEmpty())
        assertTrue(SpeedRamp.runs(floatArrayOf(5f, 6f), 100f, 0).isEmpty())
    }

    @Test
    fun unknownSpeedsDoNotBreakARun() {
        val runs = SpeedRamp.runs(floatArrayOf(Float.NaN, Float.NaN, Float.NaN), 100f, 10)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].bucket)
    }
}
