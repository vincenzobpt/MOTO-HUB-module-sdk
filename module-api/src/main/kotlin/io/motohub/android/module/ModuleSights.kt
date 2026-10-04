// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * Things worth a detour near a road, from OpenStreetMap, lent to a module (contract 22).
 *
 * The app owns the Overpass side: which public instances to ask, how they are spaced, what is
 * cached, how a failing one is left alone. A module says what it wants and where, and gets named
 * points back. Blocking network calls: call them off the main thread. A failure is an ordinary
 * answer, never an exception.
 */
interface ModuleSights {

    /**
     * The sights of the kinds in [kinds] (a mask of [ModuleSightKind] values) within
     * [radiusMeters] of the road through [latitudes]/[longitudes] in order (at least two points).
     * The radius is clamped to [ModuleSightKind.MIN_RADIUS_METERS]..[ModuleSightKind.MAX_RADIUS_METERS].
     *
     * Every sight has a name: a viewpoint or a ruin with nothing to call it by is left out, since a
     * module offers them by name. Sights come in no particular order; the caller orders them along
     * its own road. A road longer than the search covers still answers, with
     * [ModuleSightResult.complete] false.
     */
    fun along(latitudes: DoubleArray, longitudes: DoubleArray, radiusMeters: Int, kinds: Int): ModuleSightResult
}

/** What a sight is. Combined into a mask for [ModuleSights.along]; one value on each sight returned. */
object ModuleSightKind {
    /** A viewpoint, a waterfall, a cave entrance or a natural arch. */
    const val VIEWPOINT = 1

    /** A castle, a fort, ruins, an abbey, a monument, a museum, a notable church. */
    const val HERITAGE = 2

    /**
     * A named mountain pass the road itself climbs (the radius does not widen it: a pass a couple
     * of kilometres off the line is not one the road crosses). [ModuleSightResult.elevations] holds
     * its height when OSM has one.
     */
    const val PASS = 4

    const val ALL = VIEWPOINT or HERITAGE or PASS

    const val MIN_RADIUS_METERS = 500
    const val MAX_RADIUS_METERS = 15_000
}

/**
 * Sights, or why there are none. The arrays are parallel, one entry per sight. [kinds] holds one
 * [ModuleSightKind] value each; [elevations] is NaN where the height is not known.
 * [complete] is false when the road ran on past what the search covered.
 */
class ModuleSightResult(
    val ok: Boolean,
    val error: String?,
    val complete: Boolean,
    val names: Array<String>,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val kinds: IntArray,
    val elevations: DoubleArray
)
