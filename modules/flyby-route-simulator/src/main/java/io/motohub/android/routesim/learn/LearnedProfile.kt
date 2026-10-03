// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// What the simulator learned from the rider's own rides, and how it is applied to a profile.
// Plain Kotlin, no Android.
package io.motohub.android.routesim.learn

import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle

/**
 * The rider's numbers, expressed for a NORMAL-style rider: [withLearned] scales them to CALM and
 * SPORTY by the same ratios the built-in styles have to each other.
 *
 * A null field was not learned (not enough data) and leaves the profile as it was.
 */
class LearnedProfile(
    val learnedAtMillis: Long,
    val ridesUsed: Int,
    val kmUsed: Double,
    /** Rides that carried engine-speed data (an OBD adapter). */
    val rpmRidesUsed: Int,
    /** Degrees. */
    val maxLeanDeg: Double?,
    /** Cornering budget, m/s^2. */
    val lateralAccelMs2: Double?,
    /** Typical hard-ish acceleration, m/s^2. */
    val accelMs2: Double?,
    /** Typical braking, m/s^2. */
    val brakeMs2: Double?,
    /** Ridden speed over the speed limit (1.0 = on the limit). */
    val overLimitFactor: Double?,
    /** rpm per (m/s), index 0 = 1st gear, descending. */
    val gearRatios: DoubleArray?,
    val idleRpm: Double?,
    val maxRpm: Double?,
    val shiftUpRpm: Double?,
    val shiftDownRpm: Double?,
) {
    /** True when at least one number was learned. */
    val hasAnything: Boolean
        get() = maxLeanDeg != null || lateralAccelMs2 != null || accelMs2 != null || brakeMs2 != null ||
            overLimitFactor != null || gearRatios != null || idleRpm != null || maxRpm != null ||
            shiftUpRpm != null || shiftDownRpm != null
}

/**
 * One ride as the learner sees it, per point and all arrays aligned (a shorter optional array
 * counts as NaN for the missing tail). [rpm] is null for a ride without engine data;
 * [speedLimitKph] is null when limits were not fetched, NaN where a point's limit is unknown.
 */
class RideSample(
    val timesMillis: LongArray,
    val speedsKph: FloatArray,
    val leanDegrees: FloatArray,
    val rpm: FloatArray?,
    val speedLimitKph: FloatArray?,
)

/** A closed range with a plain clamp (no stdlib range objects: the module borrows the app's stdlib). */
internal class Bounds(val lo: Double, val hi: Double)

internal fun Double.within(b: Bounds): Double = if (this < b.lo) b.lo else if (this > b.hi) b.hi else this

/** Sane bounds of every learned number; what is learned and what is applied is clamped to these. */
internal object LearnedRanges {
    val ACCEL = Bounds(1.0, 5.0)
    val BRAKE = Bounds(1.5, 6.0)
    val LATERAL = Bounds(1.5, 7.0)
    val OVER_LIMIT = Bounds(0.7, 1.3)
    val MAX_LEAN = Bounds(20.0, 60.0)
    val IDLE = Bounds(700.0, 2000.0)
    val MAX_RPM = Bounds(6000.0, 14000.0)
    val SHIFT_UP = Bounds(3000.0, 13000.0)
    val SHIFT_DOWN = Bounds(1200.0, 6000.0)

    // A CALM or SPORTY version of a learned NORMAL number may fall a little outside the learned
    // range; these are the limits of what the engine is asked to simulate.
    val ACCEL_OUT = Bounds(0.8, 5.5)
    val BRAKE_OUT = Bounds(1.2, 6.5)
    val LATERAL_OUT = Bounds(1.2, 7.5)
    val OVER_LIMIT_OUT = Bounds(0.6, 1.4)
    val MAX_LEAN_OUT = Bounds(18.0, 62.0)

    /** The upshift point stays this far under the rev limiter, and above the downshift point by [MIN_SHIFT_GAP_RPM]. */
    const val SHIFT_UP_OF_MAX = 0.95
    const val MIN_SHIFT_GAP_RPM = 1000.0
}

/**
 * This profile with the rider's learned numbers in place of the built-in ones.
 *
 * For accel, brake, lateral, over-limit, max lean and the two shift points the result is the
 * learned NORMAL-rider number times (generic(style) / generic(NORMAL)), so the three styles keep
 * their relation to each other. Gear ratios, idle and the rev limit are replaced as they are.
 * A null [l] or a null field leaves the matching value untouched. Results are clamped to sane
 * ranges, and the upshift point never ends above the rev limiter.
 */
fun DrivingProfile.withLearned(l: LearnedProfile?): DrivingProfile {
    if (l == null) return this
    val g = DrivingProfile.generic(style)
    val n = DrivingProfile.generic(RideStyle.NORMAL)

    fun scaled(learned: Double?, current: Double, styleValue: Double, normalValue: Double, range: Bounds): Double {
        if (learned == null || !learned.isFinite()) return current
        val ratio = if (normalValue > 0.0) styleValue / normalValue else 1.0
        return (learned * ratio).within(range)
    }

    fun plain(learned: Double?, current: Double, range: Bounds): Double =
        if (learned == null || !learned.isFinite()) current else learned.within(range)

    val idle = plain(l.idleRpm, idleRpm, LearnedRanges.IDLE)
    val max = plain(l.maxRpm, maxRpm, LearnedRanges.MAX_RPM)
    val down = scaled(l.shiftDownRpm, shiftDownRpm, g.shiftDownRpm, n.shiftDownRpm, LearnedRanges.SHIFT_DOWN)
    val upWanted = scaled(l.shiftUpRpm, shiftUpRpm, g.shiftUpRpm, n.shiftUpRpm, LearnedRanges.SHIFT_UP)
    val upHigh = max * LearnedRanges.SHIFT_UP_OF_MAX
    val upLow = Math.min(down + LearnedRanges.MIN_SHIFT_GAP_RPM, upHigh)
    // Untouched unless something about the engine's speed range was learned.
    val touchesShifts = l.shiftUpRpm != null || l.shiftDownRpm != null || l.maxRpm != null
    val up = if (touchesShifts) Math.min(Math.max(upWanted, upLow), upHigh) else shiftUpRpm

    val gears = l.gearRatios
        ?.takeIf { it.isNotEmpty() && it.all { r -> r.isFinite() && r > 0.0 } }
        ?.copyOf()
        ?: gearRatios.copyOf()

    return copy(
        accelMs2 = scaled(l.accelMs2, accelMs2, g.accelMs2, n.accelMs2, LearnedRanges.ACCEL_OUT),
        brakeMs2 = scaled(l.brakeMs2, brakeMs2, g.brakeMs2, n.brakeMs2, LearnedRanges.BRAKE_OUT),
        lateralAccelMs2 = scaled(l.lateralAccelMs2, lateralAccelMs2, g.lateralAccelMs2, n.lateralAccelMs2, LearnedRanges.LATERAL_OUT),
        overLimitFactor = scaled(l.overLimitFactor, overLimitFactor, g.overLimitFactor, n.overLimitFactor, LearnedRanges.OVER_LIMIT_OUT),
        maxLeanDeg = scaled(l.maxLeanDeg, maxLeanDeg, g.maxLeanDeg, n.maxLeanDeg, LearnedRanges.MAX_LEAN_OUT),
        idleRpm = idle,
        maxRpm = max,
        shiftUpRpm = up,
        shiftDownRpm = down,
        gearRatios = gears,
    )
}
