// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * The NAV's place search, lent to a module (contract 22). Blocking network calls: call them off
 * the main thread. The caller debounces; a query over 200 characters is refused.
 */
interface ModulePlaces {

    /** Places matching [query], nearest to ([nearLatitude], [nearLongitude]) first; NaN for no bias. */
    fun search(query: String, nearLatitude: Double, nearLongitude: Double, limit: Int): ModulePlaceResult

    /** The name of the place at a point (a town or a pass), or null when there is none. */
    fun reverse(latitude: Double, longitude: Double): String?

    /**
     * The rider's last known position as [latitude, longitude], or null when the app has none: the
     * location permission is not granted, no position is known, or the one it holds is a week old
     * or more. It never asks for the permission, never waits for a fix and never throws. Cheap
     * enough to call from any thread; meant for a coarse use such as choosing a starting country,
     * not for tracking.
     */
    fun lastKnownPosition(): DoubleArray?

    /**
     * Where the rider is now (contract 32): the phone's latest GPS fix, at most ten seconds old,
     * or null when there is none or the location permission is not granted. Never asks for the
     * permission and never waits; poll it, every few seconds at most, while a screen that needs
     * it is open.
     */
    fun livePosition(): ModuleFix? = null

    /**
     * What the rider is looking at on the NAV map (contract 32): [latitude, longitude, zoom] of
     * its centre, where the app draws a small reticle while a [ModuleFeaturePlacement.NAV_PANEL]
     * is open. Updated each time the map comes to rest; null while the NAV map is not on screen.
     * Cheap; poll it while a panel is open.
     */
    fun mapCenter(): DoubleArray? = null
}

class ModulePlaceResult(
    val ok: Boolean,
    val error: String?,
    val labels: Array<String>,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray
)
