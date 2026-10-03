// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Valhalla draws a straight road with a point every few hundred metres; the simulation wants a
// point at least every 25 m, so the gaps are filled in here.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.LatLng
import io.motohub.android.routesim.sim.SimRoute
import kotlin.math.ceil

internal object Densify {

    /** No segment of the result is longer than this. */
    const val MAX_SEGMENT_M = 25.0

    /** Slightly under the maximum, so rounding in the distance formula never tips a piece over it. */
    private const val TARGET_PIECE_M = MAX_SEGMENT_M - 0.01

    /** Segments shorter than this are repeated points and are dropped. */
    private const val MIN_SEGMENT_M = 0.05

    /**
     * Inserts points along every long segment (linear interpolation of position and altitude)
     * and gives each piece the speed limit of the segment it came from.
     *
     * [altitudes] has one value per point and [limits] one per segment, NaN where unknown; both
     * must already have those sizes.
     */
    fun densify(lats: DoubleArray, lons: DoubleArray, altitudes: DoubleArray, limits: FloatArray): SimRoute {
        val n = lats.size
        require(n >= 1 && lons.size == n && altitudes.size == n && limits.size == maxOf(0, n - 1))

        val pieces = IntArray(maxOf(0, n - 1))
        var total = 1
        for (i in 0 until n - 1) {
            val len = LatLng(lats[i], lons[i]).distanceTo(LatLng(lats[i + 1], lons[i + 1]))
            pieces[i] = if (len < MIN_SEGMENT_M) 0 else ceil(len / TARGET_PIECE_M).toInt().coerceAtLeast(1)
            total += pieces[i]
        }

        val outLat = DoubleArray(total)
        val outLon = DoubleArray(total)
        val outAlt = DoubleArray(total)
        val outLimit = FloatArray(total - 1)
        outLat[0] = lats[0]; outLon[0] = lons[0]; outAlt[0] = altitudes[0]

        var w = 1
        for (i in 0 until n - 1) {
            val k = pieces[i]
            for (j in 1..k) {
                val t = j.toDouble() / k
                if (j == k) {
                    outLat[w] = lats[i + 1]; outLon[w] = lons[i + 1]; outAlt[w] = altitudes[i + 1]
                } else {
                    outLat[w] = lats[i] + (lats[i + 1] - lats[i]) * t
                    outLon[w] = lons[i] + (lons[i + 1] - lons[i]) * t
                    outAlt[w] = altitudes[i] + (altitudes[i + 1] - altitudes[i]) * t   // NaN stays NaN
                }
                outLimit[w - 1] = limits[i]
                w++
            }
        }
        return SimRoute(outLat, outLon, outAlt, outLimit)
    }
}
