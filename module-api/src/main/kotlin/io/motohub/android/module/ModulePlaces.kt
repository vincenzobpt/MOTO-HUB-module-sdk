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
}

class ModulePlaceResult(
    val ok: Boolean,
    val error: String?,
    val labels: Array<String>,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray
)
