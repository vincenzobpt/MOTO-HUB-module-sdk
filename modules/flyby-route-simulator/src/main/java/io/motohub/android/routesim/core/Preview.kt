// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.SimOutput
import kotlin.math.abs

/** What the preview charts need, downsampled to at most the requested number of points. */
class PreviewSeries(
    val timesSeconds: FloatArray,
    val speedKph: FloatArray,
    val altitudeM: FloatArray,
    val leanDeg: FloatArray,
    val rpm: FloatArray,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
)

/**
 * One point per stretch of the ride, the first and the last sample always among them. Evenly spaced
 * samples lost 2-4 s hairpin lean peaks on long rides: each point is the stretch's sample leaning most,
 * and its speed the stretch's extreme farthest from the point before, so short peaks and stops show too.
 */
fun previewSeries(out: SimOutput, maxPoints: Int): PreviewSeries {
    val n = out.timesMillis.size
    val m = if (n == 0) 0 else minOf(n, maxOf(2, maxPoints))
    val idx = IntArray(m)
    val speed = FloatArray(m)
    for (k in 0 until m) {
        if (k == 0 || k == m - 1) {
            idx[k] = if (k == 0) 0 else n - 1
            speed[k] = out.speedsKph[idx[k]]
            continue
        }
        // Samples 1..n-2 split into m-2 stretches, none empty since m <= n.
        val lo = 1 + ((k - 1).toLong() * (n - 2) / (m - 2)).toInt()
        val hi = 1 + (k.toLong() * (n - 2) / (m - 2)).toInt()
        var best = lo
        var low = out.speedsKph[lo]
        var high = low
        for (i in lo until hi) {
            if (abs(out.leanDegrees[i]) > abs(out.leanDegrees[best])) best = i
            low = minOf(low, out.speedsKph[i])
            high = maxOf(high, out.speedsKph[i])
        }
        idx[k] = best
        val before = speed[k - 1]
        speed[k] = if (high - before >= before - low) high else low
    }
    return PreviewSeries(
        timesSeconds = FloatArray(m) { out.timesMillis[idx[it]] / 1000f },
        speedKph = speed,
        altitudeM = FloatArray(m) { out.altitudesMeters[idx[it]].toFloat() },
        leanDeg = FloatArray(m) { out.leanDegrees[idx[it]] },
        rpm = FloatArray(m) { out.engineRpm[idx[it]] },
        latitudes = DoubleArray(m) { out.latitudes[idx[it]] },
        longitudes = DoubleArray(m) { out.longitudes[idx[it]] },
    )
}
