// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChartMathTest {

    @Test
    fun roundsUpToANiceNumber() {
        val cases = listOf(0f to 1f, -4f to 1f, 1f to 1f, 1.5f to 2f, 2.2f to 2.5f, 3f to 5f, 7f to 10f, 87f to 100f, 130f to 200f, 0.3f to 0.5f, 9500f to 10000f)
        for ((input, expected) in cases) assertEquals("niceCeil($input)", expected, ChartMath.niceCeil(input), expected * 1e-5f)
    }

    @Test
    fun speedAndRpmStartAtZero() {
        val speed = ChartMath.axis(floatArrayOf(0f, 40f, 87.3f), ChartKind.SPEED)!!
        assertEquals(0f, speed.min, 0f)
        assertEquals(100f, speed.max, 0f)
        val rpm = ChartMath.axis(floatArrayOf(1300f, 800f), ChartKind.RPM)!!
        assertEquals(0f, rpm.min, 0f)
        assertEquals(2000f, rpm.max, 0f)
        // A ride that never moved still gets a readable axis.
        assertEquals(20f, ChartMath.axis(floatArrayOf(0f, 0f), ChartKind.SPEED)!!.max, 0f)
    }

    @Test
    fun leanIsSymmetricAroundZero() {
        val lean = ChartMath.axis(floatArrayOf(-30f, 20f, Float.NaN), ChartKind.LEAN)!!
        assertEquals(-50f, lean.min, 0f)
        assertEquals(50f, lean.max, 0f)
        assertEquals(-10f, ChartMath.axis(floatArrayOf(1f), ChartKind.LEAN)!!.min, 0f)
    }

    @Test
    fun altitudeFollowsTheRouteWithRoomAround() {
        val flat = ChartMath.axis(floatArrayOf(100f, 110f), ChartKind.ALTITUDE)!!
        assertEquals(93.4f, flat.min, 1e-3f)
        assertEquals(116.6f, flat.max, 1e-3f)
        val climb = ChartMath.axis(floatArrayOf(300f, 2300f), ChartKind.ALTITUDE)!!
        assertEquals(1300f, (climb.min + climb.max) / 2f, 1e-2f)
        assertEquals(2000f * 1.16f, climb.span, 1e-1f)
    }

    @Test
    fun noFiniteValueMeansNoAxis() {
        assertNull(ChartMath.axis(floatArrayOf(), ChartKind.SPEED))
        assertNull(ChartMath.axis(floatArrayOf(Float.NaN, Float.NaN), ChartKind.ALTITUDE))
        assertNotNull(ChartMath.axis(floatArrayOf(Float.NaN, 5f), ChartKind.ALTITUDE))
    }

    @Test
    fun theCursorLandsOnTheNearestSample() {
        val t = floatArrayOf(0f, 10f, 20f, 30f)
        assertEquals(0, ChartMath.cursorIndex(t, 0f))
        assertEquals(3, ChartMath.cursorIndex(t, 1f))
        assertEquals(1, ChartMath.cursorIndex(t, 0.5f))
        assertEquals(3, ChartMath.cursorIndex(t, 0.9f))
        assertEquals(0, ChartMath.cursorIndex(t, Float.NaN))
        assertEquals(0, ChartMath.cursorIndex(t, -2f))
        assertEquals(3, ChartMath.cursorIndex(t, 7f))
        assertEquals(0, ChartMath.cursorIndex(floatArrayOf(), 0.5f))
        assertEquals(0, ChartMath.cursorIndex(floatArrayOf(5f), 0.5f))
    }

    @Test
    fun cursorFollowsUnevenTimeSteps() {
        val t = floatArrayOf(0f, 1f, 2f, 100f)
        // Half way through the time is closest to the second-last sample, not to the middle index.
        assertEquals(2, ChartMath.cursorIndex(t, 0.5f))
        assertEquals(3, ChartMath.cursorIndex(t, 0.6f))
        assertEquals(1f / 100f, ChartMath.fractionOfIndex(t, 1), 1e-6f)
        assertEquals(1f, ChartMath.fractionOfIndex(t, 3), 0f)
        assertEquals(0f, ChartMath.fractionOfIndex(floatArrayOf(5f), 0), 0f)
    }

    @Test
    fun touchesMapToTheRidesTime() {
        assertEquals(0f, ChartMath.fractionOfX(10f, 100f, 10f, 10f), 0f)
        assertEquals(0.5f, ChartMath.fractionOfX(50f, 100f, 10f, 10f), 1e-6f)
        assertEquals(0f, ChartMath.fractionOfX(-30f, 100f, 10f, 10f), 0f)
        assertEquals(1f, ChartMath.fractionOfX(400f, 100f, 10f, 10f), 0f)
        assertEquals(0f, ChartMath.fractionOfX(5f, 20f, 10f, 10f), 0f)
        assertEquals(50f, ChartMath.xOfFraction(0.5f, 100f, 10f, 10f), 1e-6f)
    }

    @Test
    fun valuesMapToTheChartsHeight() {
        val range = AxisRange(0f, 100f)
        assertEquals(90f, ChartMath.yOfValue(0f, range, 10f, 80f), 1e-4f)
        assertEquals(10f, ChartMath.yOfValue(100f, range, 10f, 80f), 1e-4f)
        assertEquals(50f, ChartMath.yOfValue(50f, range, 10f, 80f), 1e-4f)
        assertEquals(90f, ChartMath.yOfValue(5f, AxisRange(5f, 5f), 10f, 80f), 1e-4f)
    }
}
