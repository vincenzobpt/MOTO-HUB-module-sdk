// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The words the calibration puts in front of the rider (progress, the summary of what was learned,
// the reasons it learned nothing), in one place so another language can be added without
// touching the logic.
package io.motohub.android.routesim.learn

import java.util.Locale

internal object LearnStrings {
    const val LISTING = "Looking through your rides…"
    const val ANALYSING = "Working out how you ride…"
    const val NOTHING_LEARNED = "Nothing could be learned from these rides. They may be too short or lack lean and speed data."

    const val FACT_RIDES = "Rides used"
    const val FACT_LEAN = "Maximum lean"
    const val FACT_CORNERING = "Cornering"
    const val FACT_ACCEL = "Typical acceleration"
    const val FACT_BRAKE = "Typical braking"
    const val FACT_LIMITS = "Speed against the limit"
    const val FACT_ENGINE = "Engine speed"
    const val FACT_GEARS = "Gears"
    const val FACT_SHIFTS = "Shift points"

    const val NOT_LEARNED = "not learned, using the generic value"
    const val LIMITS_NOT_LEARNED = "not learned (no speed limit data), using the generic style"
    const val ENGINE_GENERIC_NO_OBD = "generic (no ride with an OBD adapter)"
    const val ENGINE_GENERIC_NOT_ENOUGH = "generic (not enough engine data from your OBD rides)"
    const val GEARS_NOT_LEARNED = "generic (the gears could not be told apart)"
    const val SHIFTS_NOT_LEARNED = "generic (too few gear changes seen)"

    fun needMoreRides(needed: Int, minKm: Int, have: Int): String =
        "$needed rides of at least $minKm km are needed, you have $have"

    fun unreadableRides(needed: Int, readable: Int): String =
        "Only $readable of your rides could be read. $needed are needed."

    fun readingRide(position: Int, total: Int): String = "Reading ride $position of $total…"

    fun lookingUpLimits(position: Int, total: Int): String = "Looking up the speed limits of ride $position of $total…"

    fun rides(count: Int, km: Double): String =
        "$count ${if (count == 1) "ride" else "rides"}, ${format(km, 0)} km"

    fun degrees(deg: Double): String = "${format(deg, 0)}°"

    fun accel(ms2: Double): String = "${format(ms2, 1)} m/s²"

    fun cornering(ms2: Double): String = "${format(ms2, 1)} m/s² sideways"

    fun againstLimit(factor: Double): String {
        val percent = Math.round((factor - 1.0) * 100.0).toInt()
        return when {
            percent > 0 -> "about $percent% over the limit"
            percent < 0 -> "about ${-percent}% under the limit"
            else -> "right at the limit"
        }
    }

    fun gears(count: Int, rpmRides: Int): String =
        "$count ratios learned from $rpmRides ${if (rpmRides == 1) "ride" else "rides"} with an OBD adapter"

    fun engine(idle: Double?, maxRpm: Double?): String {
        val parts = ArrayList<String>()
        if (idle != null) parts.add("idle ${format(idle, 0)} rpm")
        if (maxRpm != null) parts.add("up to ${format(maxRpm, 0)} rpm")
        return join(parts)
    }

    fun shifts(up: Double?, down: Double?): String {
        val parts = ArrayList<String>()
        if (up != null) parts.add("up at ${format(up, 0)} rpm")
        if (down != null) parts.add("down at ${format(down, 0)} rpm")
        return join(parts)
    }

    private fun join(parts: List<String>): String {
        val sb = StringBuilder()
        for (i in 0 until parts.size) {
            if (i > 0) sb.append(", ")
            sb.append(parts[i])
        }
        return sb.toString()
    }

    private fun format(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)
}
