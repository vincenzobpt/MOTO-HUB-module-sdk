// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * The NAV's routing, speed limits and elevation, lent to a module (contract 22).
 *
 * The app picks the server (the rider's own key, or the shared demo server) and keeps to its
 * rate limits. Every call goes to the network and blocks: call it off the main thread. A failure
 * is an ordinary answer, never an exception.
 */
interface ModuleRouting {

    /**
     * A motorcycle route through [latitudes]/[longitudes] in order (at least two points). The
     * first and last are the start and the destination; any others are points to pass through.
     * [preference] is [ModuleRoutePreference.FASTEST] or [ModuleRoutePreference.SCENIC].
     */
    fun route(latitudes: DoubleArray, longitudes: DoubleArray, preference: Int): ModuleRouteResult

    /**
     * The speed limit in km/h of each segment of a route, one value per segment (size = points
     * minus one), NaN where the road has none known. Returns null when the limits could not be
     * fetched; the caller then falls back on its own cruising speed.
     */
    fun speedLimits(latitudes: DoubleArray, longitudes: DoubleArray): FloatArray?

    /** Metres above sea level at each point, or null when the elevation service did not answer. */
    fun elevations(latitudes: DoubleArray, longitudes: DoubleArray): DoubleArray?
}

object ModuleRoutePreference {
    const val FASTEST = 0
    const val SCENIC = 1
}

object ModuleRouteError {
    const val NONE = 0
    const val NO_NETWORK = 1
    const val RATE_LIMITED = 2
    const val NO_API_KEY = 3
    const val TOO_LONG = 4
    const val OTHER = 5
}

/** A route, or why there is none. [errorKind] is a [ModuleRouteError]; [error] is in the rider's words. */
class ModuleRouteResult(
    val ok: Boolean,
    val errorKind: Int,
    val error: String?,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val distanceMeters: Double,
    val durationSeconds: Double
)
