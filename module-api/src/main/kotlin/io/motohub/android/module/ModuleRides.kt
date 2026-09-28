// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * The rider's rides and routes, read-only, for a module that tells stories with them.
 *
 * The app keeps these in its own stores under names R8 rewrites, so a module could not reach them
 * even if it knew where they were. This is the whole of what it gets: what exists, and one track
 * at a time as plain arrays.
 *
 * Every call reads storage. Call it off the main thread.
 */
interface ModuleRideLibrary {

    /** Rides the app recorded, newest first, at most [limit]. */
    fun recordedRides(limit: Int): List<ModuleRideEntry>

    /** Routes the rider saved - planned in the NAV or imported from a GPX file - in their own order. */
    fun savedRoutes(limit: Int): List<ModuleRideEntry>

    /** The route the NAV is guiding along right now, or null when nothing is being navigated. */
    fun activeRoute(): ModuleRideEntry?

    /**
     * One entry's track, thinned evenly to at most [maxPoints] points, or null when the entry no
     * longer exists (deleted, or a route that stopped being active).
     */
    fun track(entry: ModuleRideEntry, maxPoints: Int): ModuleRideTrack?

    /**
     * Engine speed in rpm at each point of [track], from the OBD log recorded with the ride, NaN
     * where the log has nothing; null when there is no engine data at all - a planned route, or a
     * ride recorded without an OBD adapter (contract 12).
     */
    fun engineRpm(track: ModuleRideTrack): FloatArray?
}

/**
 * One ride or route, as a list shows it.
 *
 * [dateMillis] is when the ride happened, or when a route was saved; [durationMillis] is the
 * moving time of a ride and the planned time of a route. [kind] is one of the KIND_ constants and
 * is what [ModuleRideLibrary.track] reads to know which store to open - so an entry is handed back
 * as it was received, not rebuilt.
 */
class ModuleRideEntry(
    val id: String,
    val kind: Int,
    val title: String,
    val dateMillis: Long,
    val distanceMeters: Double,
    val durationMillis: Long
) {
    companion object {
        const val KIND_RECORDED = 1
        const val KIND_SAVED_ROUTE = 2
        const val KIND_ACTIVE_ROUTE = 3
        /** A route planned and previewed but neither saved nor navigated: readable while its preview is open. */
        const val KIND_PREVIEW_ROUTE = 4
    }
}

/**
 * A track as parallel arrays, one slot per point, all the same length.
 *
 * Unknown values are NaN rather than absent, so the arrays stay aligned: a planned route has no
 * measured speed and no lean, and a ride recorded without the sensor log has no lean either.
 *
 * - [altitudesMeters]: above sea level, from the GPS or the route's elevation profile.
 * - [timesMillis]: since the first point. For a route, the planned time at that point.
 * - [speedsKph]: measured speed; NaN on a route.
 * - [leanDegrees]: the lean the app measured, NaN where it was not trustworthy.
 * - [maneuverIndices] / [maneuverTexts]: where a turn instruction applies and what it says, for a
 *   route; empty for a recorded ride.
 */
class ModuleRideTrack(
    val entry: ModuleRideEntry,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val altitudesMeters: DoubleArray,
    val timesMillis: LongArray,
    val speedsKph: FloatArray,
    val leanDegrees: FloatArray,
    val maneuverIndices: IntArray,
    val maneuverTexts: Array<String>
)
