// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationSamplingTest {

    /** A straight road north from 46°N, one point every [stepM] metres. */
    private fun road(points: Int, stepM: Double = 10.0): Pair<DoubleArray, DoubleArray> {
        val degPerM = 1.0 / 111_195.0
        return Pair(DoubleArray(points) { 46.0 + it * stepM * degPerM }, DoubleArray(points) { 11.0 })
    }

    @Test
    fun aShortRoadIsAskedForEveryPoint() {
        val (lats, lons) = road(3)
        assertArrayEquals(intArrayOf(0, 1, 2), ElevationSampling.sampleIndices(lats, lons))
    }

    @Test
    fun aLongRoadIsAskedForAtMostTheCapIncludingBothEnds() {
        val (lats, lons) = road(5_000)   // 50 km in 10 m steps
        val picked = ElevationSampling.sampleIndices(lats, lons)
        assertTrue("${picked.size} samples", picked.size <= ElevationSampling.MAX_SAMPLES)
        assertTrue("${picked.size} samples", picked.size >= ElevationSampling.MAX_SAMPLES - 2)
        assertEquals(0, picked.first())
        assertEquals(4_999, picked.last())
        for (k in 1 until picked.size) assertTrue(picked[k] > picked[k - 1])
    }

    @Test
    fun samplesAreNeverCloserThanTheMinimumSpacing() {
        val (lats, lons) = road(400, stepM = 5.0)   // 2 km: the spacing, not the cap, decides
        val picked = ElevationSampling.sampleIndices(lats, lons, maxSamples = 300)
        assertTrue("${picked.size} samples", picked.size <= 22)
    }

    @Test
    fun pointsBetweenSamplesFollowTheLineByDistance() {
        val (lats, lons) = road(5)
        val out = ElevationSampling.spread(lats, lons, intArrayOf(0, 4), doubleArrayOf(1_000.0, 1_400.0))
        assertArrayEquals(doubleArrayOf(1_000.0, 1_100.0, 1_200.0, 1_300.0, 1_400.0), out, 0.5)
    }

    @Test
    fun anUnknownSampleLeavesItsNeighboursUnknown() {
        val (lats, lons) = road(5)
        val out = ElevationSampling.spread(lats, lons, intArrayOf(0, 2, 4), doubleArrayOf(100.0, Double.NaN, 300.0))
        assertEquals(100.0, out[0], 0.0)
        assertTrue(out[1].isNaN())
        assertTrue(out[2].isNaN())
        assertTrue(out[3].isNaN())
        assertEquals(300.0, out[4], 0.0)
    }

    @Test
    fun mismatchedAnswersGiveNothing() {
        val (lats, lons) = road(5)
        val out = ElevationSampling.spread(lats, lons, intArrayOf(0, 4), doubleArrayOf(1.0))
        assertTrue(out.all { it.isNaN() })
    }
}
