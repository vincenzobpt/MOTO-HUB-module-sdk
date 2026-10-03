// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.SimOutput

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

/** Evenly spaced samples, the first and the last always among them. */
fun previewSeries(out: SimOutput, maxPoints: Int): PreviewSeries {
    val n = out.timesMillis.size
    val m = if (n == 0) 0 else minOf(n, maxOf(2, maxPoints))
    val idx = IntArray(m) { k ->
        if (m == 1) 0 else Math.round(k.toDouble() * (n - 1) / (m - 1)).toInt()
    }
    return PreviewSeries(
        timesSeconds = FloatArray(m) { out.timesMillis[idx[it]] / 1000f },
        speedKph = FloatArray(m) { out.speedsKph[idx[it]] },
        altitudeM = FloatArray(m) { out.altitudesMeters[idx[it]].toFloat() },
        leanDeg = FloatArray(m) { out.leanDegrees[idx[it]] },
        rpm = FloatArray(m) { out.engineRpm[idx[it]] },
        latitudes = DoubleArray(m) { out.latitudes[idx[it]] },
        longitudes = DoubleArray(m) { out.longitudes[idx[it]] },
    )
}
