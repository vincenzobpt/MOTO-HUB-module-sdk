// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.LatLng

/**
 * The elevations of a routed road from a bounded number of lookups.
 *
 * A router's polyline puts a point every few metres through a hairpin: a 55 km pass loop is well
 * over a thousand points, and the host's elevation service (Open-Meteo) counts every point against a
 * per-minute limit. A lookup that fails in any batch is thrown away whole, and on the Sella Ronda
 * the Altitude chart came out empty. So the road is sampled
 * evenly by distance, at most [MAX_SAMPLES] points, and the points between two samples get the
 * straight line between their heights. A terrain model read 90 m apart says nothing finer anyway.
 */
internal object ElevationSampling {

    /** The most points asked for in one road, whatever its length. */
    const val MAX_SAMPLES = 300

    /** Samples closer than this along the road add nothing a rider could see in the chart. */
    const val MIN_SPACING_M = 100.0

    /**
     * Indices of the points to look up: the first, the last, and points in between spread evenly by
     * distance along the road, never more than [maxSamples]. Every point when there are few enough.
     */
    fun sampleIndices(
        lats: DoubleArray,
        lons: DoubleArray,
        maxSamples: Int = MAX_SAMPLES,
        minSpacingM: Double = MIN_SPACING_M,
    ): IntArray {
        val n = lats.size
        if (n <= maxSamples) return IntArray(n) { it }
        val along = cumulativeMeters(lats, lons)
        val total = along[n - 1]
        val spacing = Math.max(minSpacingM, total / (maxSamples - 1))
        val picked = ArrayList<Int>()
        picked.add(0)
        // Targets at whole multiples of the spacing, so rounding to the nearest point never drifts.
        var next = spacing
        for (i in 1 until n - 1) {
            if (along[i] >= next) {
                picked.add(i)
                while (next <= along[i]) next += spacing
            }
        }
        picked.add(n - 1)
        return IntArray(picked.size) { picked[it] }
    }

    /**
     * One elevation per point of the road: the [sampled] values at [indices], and the straight line
     * between the two samples around every other point, by distance along the road. A sample that is
     * NaN stays unknown, and so does every point that leans on it.
     */
    fun spread(lats: DoubleArray, lons: DoubleArray, indices: IntArray, sampled: DoubleArray): DoubleArray {
        val n = lats.size
        val out = DoubleArray(n) { Double.NaN }
        if (indices.isEmpty() || sampled.size != indices.size) return out
        val along = cumulativeMeters(lats, lons)
        for (k in indices.indices) out[indices[k]] = sampled[k]
        for (k in 0 until indices.size - 1) {
            val a = indices[k]
            val b = indices[k + 1]
            val span = along[b] - along[a]
            for (i in a + 1 until b) {
                val t = if (span > 0.0) (along[i] - along[a]) / span else 0.0
                out[i] = sampled[k] + (sampled[k + 1] - sampled[k]) * t   // NaN stays NaN
            }
        }
        return out
    }

    private fun cumulativeMeters(lats: DoubleArray, lons: DoubleArray): DoubleArray {
        val n = lats.size
        val along = DoubleArray(n)
        for (i in 1 until n) {
            along[i] = along[i - 1] + LatLng(lats[i - 1], lons[i - 1]).distanceTo(LatLng(lats[i], lons[i]))
        }
        return along
    }
}
