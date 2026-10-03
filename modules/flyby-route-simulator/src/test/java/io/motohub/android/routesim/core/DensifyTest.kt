// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DensifyTest {

    private fun maxSegment(lats: DoubleArray, lons: DoubleArray): Double {
        var max = 0.0
        for (i in 0 until lats.size - 1) max = maxOf(max, LatLng(lats[i], lons[i]).distanceTo(LatLng(lats[i + 1], lons[i + 1])))
        return max
    }

    @Test
    fun noSegmentIsLongerThan25MetresAndEndpointsAreKept() {
        val lats = doubleArrayOf(45.0, 45.0, 45.01)
        val lons = doubleArrayOf(7.0, 7.02, 7.02)
        val r = Densify.densify(lats, lons, doubleArrayOf(100.0, 200.0, 150.0), floatArrayOf(50f, 90f))
        assertTrue(maxSegment(r.lats, r.lons) <= 25.0)
        assertEquals(r.lats.size, r.lons.size)
        assertEquals(r.lats.size, r.altitudesMeters.size)
        assertEquals(r.lats.size - 1, r.speedLimitKph.size)
        assertEquals(45.0, r.lats.first(), 0.0)
        assertEquals(7.0, r.lons.first(), 0.0)
        assertEquals(45.01, r.lats.last(), 0.0)
        assertEquals(7.02, r.lons.last(), 0.0)
        assertTrue(r.lats.size > 100)
    }

    @Test
    fun limitsAndAltitudesAreCarried() {
        val r = Densify.densify(
            doubleArrayOf(45.0, 45.0, 45.0), doubleArrayOf(7.0, 7.01, 7.02),
            doubleArrayOf(100.0, 200.0, 300.0), floatArrayOf(50f, 90f),
        )
        // The first half of the pieces carries 50, the second 90, with one switch.
        val switches = (1 until r.speedLimitKph.size).count { r.speedLimitKph[it] != r.speedLimitKph[it - 1] }
        assertEquals(1, switches)
        assertEquals(50f, r.speedLimitKph.first(), 0f)
        assertEquals(90f, r.speedLimitKph.last(), 0f)
        assertEquals(100.0, r.altitudesMeters.first(), 0.0)
        assertEquals(300.0, r.altitudesMeters.last(), 0.0)
        for (i in 1 until r.altitudesMeters.size) assertTrue(r.altitudesMeters[i] >= r.altitudesMeters[i - 1])
        val mid = r.lats.indices.first { r.lons[it] >= 7.01 }
        assertEquals(200.0, r.altitudesMeters[mid], 1.0)
    }

    @Test
    fun unknownStaysUnknown() {
        val r = Densify.densify(
            doubleArrayOf(45.0, 45.0), doubleArrayOf(7.0, 7.01),
            doubleArrayOf(Double.NaN, Double.NaN), floatArrayOf(Float.NaN),
        )
        assertTrue(r.altitudesMeters.all { it.isNaN() })
        assertTrue(r.speedLimitKph.all { it.isNaN() })
    }

    @Test
    fun repeatedPointsAreDropped() {
        val r = Densify.densify(
            doubleArrayOf(45.0, 45.0, 45.0), doubleArrayOf(7.0, 7.0, 7.001),
            doubleArrayOf(1.0, 1.0, 2.0), floatArrayOf(30f, 60f),
        )
        assertTrue(r.lats.size >= 2)
        assertTrue(maxSegment(r.lats, r.lons) <= 25.0)
        for (i in 0 until r.lats.size - 1) {
            assertTrue(LatLng(r.lats[i], r.lons[i]).distanceTo(LatLng(r.lats[i + 1], r.lons[i + 1])) > 0.01)
        }
        assertTrue(r.speedLimitKph.all { it == 60f })
    }
}
