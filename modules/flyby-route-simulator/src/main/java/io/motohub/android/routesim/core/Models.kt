// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The values the planner, the generator and the saved plans pass around. Plain Kotlin, no Android.
package io.motohub.android.routesim.core

import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.SimOutput
import io.motohub.android.routesim.sim.SimRoute
import io.motohub.android.routesim.sim.TrafficLevel
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

class Stop(val latitude: Double, val longitude: Double, val label: String)

/** Everything the rider sets. [profile] starts as DrivingProfile.generic(style) and the advanced section edits copies of it. */
data class RideSettings(
    val startAtMillis: Long,
    val style: RideStyle,
    val traffic: TrafficLevel,
    val profile: DrivingProfile,
)

/** Today at 10:00 local time. When that is already past it is still today: a ride in the past is fine. */
fun defaultStartMillis(nowMillis: Long, zone: ZoneId): Long {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return today.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()
}

class Plan(val id: String, val name: String, val stops: List<Stop>, val settings: RideSettings, val savedAtMillis: Long)

/** The result of the network phase, reusable for Regenerate. */
class PreparedRoute(
    val stops: List<Stop>,
    /** Densified, with limits and elevations merged in. */
    val route: SimRoute,
    val distanceMeters: Double,
    val startPlace: String?,
    val endPlace: String?,
    /** "A → B", or coordinates where a place has no name. */
    val title: String,
)

sealed class Prepare {
    class Ok(val route: PreparedRoute) : Prepare()
    /** [kind] is a ModuleRouteError. */
    class Failed(val message: String, val kind: Int) : Prepare()
}

class GeneratedRide(val prepared: PreparedRoute, val settings: RideSettings, val seed: Long, val output: SimOutput)

sealed class Save {
    class Ok(val rideId: String) : Save()
    class Failed(val message: String) : Save()
}
