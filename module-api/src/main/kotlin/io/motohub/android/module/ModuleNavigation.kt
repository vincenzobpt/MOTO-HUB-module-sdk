// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * Turn-by-turn guidance from whatever is navigating on the motorcycle's screen.
 *
 * The app draws this on its own widgets - a rider watching the dashboard should see the next
 * turn whether the route is the app's or the projected app's - so the shape here is what a
 * widget needs, not what any one protocol sends. A module maps its own wire format onto it.
 *
 * [maneuverType] is the module's own enum value for the next turn, and -1 when it has not said.
 * Distances and times are -1 when they were not sent, which a widget must draw as absent rather
 * than as zero: "0 m to the turn" is a different and much worse thing to tell a rider.
 */
data class ModuleGuidance(
    val active: Boolean,
    val rerouting: Boolean = false,
    val maneuverType: Int = -1,
    val roundaboutExitNumber: Int = 0,
    val road: String = "",
    val distanceToManeuverMeters: Int = -1,
    val timeToManeuverSeconds: Int = -1,
    val distanceRemainingMeters: Int = -1,
    val timeToArrivalSeconds: Long = -1L,
    val estimatedTimeAtArrival: String = ""
)

fun interface ModuleGuidanceListener {
    fun onGuidance(guidance: ModuleGuidance)
}

/** The capability a module offers when what it projects can be navigating. */
interface ModuleNavigation {
    /** Delivered on every change while a session with a navigating app is running. */
    fun setGuidanceListener(listener: ModuleGuidanceListener?)
}
