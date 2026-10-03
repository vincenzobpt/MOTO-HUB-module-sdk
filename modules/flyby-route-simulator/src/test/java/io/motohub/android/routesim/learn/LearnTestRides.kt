// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.learn

import io.motohub.android.module.ModuleRideEntry
import io.motohub.android.module.ModuleRideTrack
import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideSimulator
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.SimOutput
import io.motohub.android.routesim.sim.SimRequest
import io.motohub.android.routesim.sim.SimRoute
import io.motohub.android.routesim.sim.SimTestRoutes
import io.motohub.android.routesim.sim.TrafficLevel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Rides the learner tests share: the real engine riding a winding synthetic road. */
internal object LearnTestRides {

    const val LIMIT_KPH = 90f

    /**
     * Straights and 50 m radius bends, right then left, so speed and gear keep changing:
     * 350 m straight, right 90 deg, 250 m straight, left 90 deg, and again.
     */
    fun winding(lengthM: Double, limitKph: Float): SimRoute {
        val xs = arrayListOf(0.0)
        val ys = arrayListOf(0.0)
        var x = 0.0
        var y = 0.0
        var h = 0.0 // heading from north, clockwise
        var total = 0.0
        val radius = 50.0
        fun advance(len: Double, curvature: Double) {
            var done = 0.0
            while (done < len - 1e-9) {
                val d = min(5.0, len - done)
                val mid = h + curvature * d / 2.0
                x += d * sin(mid)
                y += d * cos(mid)
                h += curvature * d
                xs.add(x); ys.add(y)
                done += d
                total += d
            }
        }
        while (total < lengthM) {
            advance(350.0, 0.0)
            advance(radius * Math.PI / 2.0, 1.0 / radius)
            advance(250.0, 0.0)
            advance(radius * Math.PI / 2.0, -1.0 / radius)
        }
        return SimTestRoutes.fromMeters(xs, ys, limitKph)
    }

    fun simulate(style: RideStyle, seed: Long, lengthM: Double, traffic: TrafficLevel): SimOutput =
        RideSimulator.run(
            SimRequest(
                route = winding(lengthM, LIMIT_KPH),
                startedAtMillis = 1_700_000_000_000L,
                profile = DrivingProfile.generic(style),
                traffic = traffic,
                seed = seed,
                sampleRateHz = 10,
            ),
        )

    fun sample(out: SimOutput, withRpm: Boolean, withLimit: Boolean): RideSample = RideSample(
        timesMillis = out.timesMillis,
        speedsKph = out.speedsKph,
        leanDegrees = out.leanDegrees,
        rpm = if (withRpm) out.engineRpm else null,
        speedLimitKph = if (withLimit) FloatArray(out.speedsKph.size) { LIMIT_KPH } else null,
    )

    fun entry(id: String, km: Double, dateMillis: Long): ModuleRideEntry =
        ModuleRideEntry(id, ModuleRideEntry.KIND_RECORDED, "Ride $id", dateMillis, km * 1000.0, 3_600_000L)

    fun track(entry: ModuleRideEntry, out: SimOutput): ModuleRideTrack = ModuleRideTrack(
        entry = entry,
        latitudes = out.latitudes,
        longitudes = out.longitudes,
        altitudesMeters = out.altitudesMeters,
        timesMillis = out.timesMillis,
        speedsKph = out.speedsKph,
        leanDegrees = out.leanDegrees,
        maneuverIndices = IntArray(0),
        maneuverTexts = emptyArray(),
    )
}
