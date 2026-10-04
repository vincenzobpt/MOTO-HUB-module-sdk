// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The values the planner, the generator and the saved plans pass around. Plain Kotlin, no Android.
package io.motohub.android.routesim.core

import io.motohub.android.module.ModuleRoutePreference
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

/**
 * A saved plan. [titleOverride] is the name the generated ride carries instead of "A → B": the
 * experience the stops came from. Null for a plan made by hand. [scenic] says the route was planned
 * with the scenic preference (an experience's card is), so it is prepared the same way again.
 */
class Plan(
    val id: String,
    val name: String,
    val stops: List<Stop>,
    val settings: RideSettings,
    val savedAtMillis: Long,
    val titleOverride: String? = null,
    val scenic: Boolean = false,
) {
    /** The [ModuleRoutePreference] this plan is routed with. */
    val routePreference: Int get() = routePreferenceOf(scenic)
}

/** The routing preference for a plan or planner that is [scenic] or not. */
fun routePreferenceOf(scenic: Boolean): Int =
    if (scenic) ModuleRoutePreference.SCENIC else ModuleRoutePreference.FASTEST

/** [text] trimmed, or null when it is null or blank: a blank name is no name. */
fun cleanTitleOverride(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

/** The result of the network phase, reusable for Regenerate. */
class PreparedRoute(
    val stops: List<Stop>,
    /** Densified, with limits and elevations merged in. */
    val route: SimRoute,
    val distanceMeters: Double,
    val startPlace: String?,
    val endPlace: String?,
    /** The ride's name: the planner's override when there is one, otherwise [defaultTitle]. */
    val title: String,
    /** "A → B", or coordinates where a place has no name. */
    val defaultTitle: String = title,
    /** The [ModuleRoutePreference] the road was routed with: a prepared route is only reusable for the same one. */
    val preference: Int = ModuleRoutePreference.FASTEST,
) {
    /** The same route under [override] as its title (or under the default one when it is blank or null). */
    fun withTitle(override: String?): PreparedRoute =
        PreparedRoute(stops, route, distanceMeters, startPlace, endPlace, cleanTitleOverride(override) ?: defaultTitle, defaultTitle, preference)
}

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
