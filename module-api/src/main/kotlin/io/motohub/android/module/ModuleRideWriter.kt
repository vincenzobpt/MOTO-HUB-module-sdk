// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * Writes rides and routes into the rider's TRIPS and NAV, for a module that generates them
 * (contract 22). Every call writes storage: call it off the main thread.
 *
 * Nothing here records a real ride. A saved ride is marked as simulated wherever the app lists
 * it, belongs to no motorcycle and stays out of every total and record.
 */
interface ModuleRideWriter {

    /** Saves [ride] as a simulated ride in TRIPS. Returns its id, or null when it could not be saved. */
    fun saveSimulatedRide(ride: ModuleSimulatedRide): String?

    /**
     * Saves [route] among the rider's simulated routes (a list of its own beside the saved
     * routes, so it never pushes a route the rider saved out). Returns its id, or null.
     */
    fun saveSimulatedRoute(route: ModuleSavedRoute): String?

    /** Removes a simulated route this module saved. Returns whether something was removed. */
    fun removeSimulatedRoute(id: String): Boolean
}

/**
 * A whole simulated ride as parallel arrays, one slot per sample, all the same length. Samples
 * are expected at 10 Hz; the app thins the GPS track the way its recorder does and keeps the
 * sensor log at full rate.
 */
class ModuleSimulatedRide(
    val title: String,
    val startedAtMillis: Long,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** Metres above sea level, NaN where unknown. */
    val altitudesMeters: DoubleArray,
    /** Milliseconds since [startedAtMillis], strictly increasing. */
    val timesMillis: LongArray,
    val speedsKph: FloatArray,
    /** Degrees, positive to the right, NaN where there is none. */
    val leanDegrees: FloatArray,
    /** NaN where there is none. */
    val engineRpm: FloatArray,
    val accuracyMeters: FloatArray,
    val satellites: IntArray,
    /** Where it started and ended, by name; null when unknown. */
    val startPlace: String?,
    val endPlace: String?
)

/** A planned route to keep, as plain arrays. */
class ModuleSavedRoute(
    val title: String,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    /** Metres above sea level, or an empty array when unknown; otherwise one per point. */
    val altitudesMeters: DoubleArray,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** The destination's name as the NAV shows it. */
    val destinationLabel: String
)
