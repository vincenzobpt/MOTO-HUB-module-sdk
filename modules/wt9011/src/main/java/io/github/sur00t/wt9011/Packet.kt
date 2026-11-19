// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.github.sur00t.wt9011

/**
 * One sample in the motorcycle's terms, after the axis constants in `Wt9011Axes`.
 *
 * Started from the model of MOTO-HUB's motion-sensor module, so [Zero] and [oriented] work the
 * same way there and here. What leaves this module is a
 * [io.motohub.android.module.ModuleMotionSample]; this class is how it gets there.
 */
data class Packet(
    /** Counted by the parser: the WT9011 sends no counter of its own. */
    val seq: Int,
    /** Arrival time on this phone, elapsedRealtime milliseconds: the WT9011 sends no timestamp. */
    val sensorMillis: Long,
    /** Degrees, + = right. */
    val leanDeg: Float,
    /** Degrees, + = nose up once [oriented] agrees with the mounting. */
    val pitchDeg: Float,
    /** Degrees, magnetic bearing of the motorcycle's nose, -180…180. */
    val yawDeg: Float,
    /** g, + = forward. Gravity included: the sensor reads specific force. */
    val longG: Float,
    /** g, + = towards the right. */
    val latG: Float,
    /** g, + = up; about +1 with the motorcycle upright and still. */
    val vertG: Float,
    /** Radians per second about forward, right-hand: + = leaning further right. */
    val rollRate: Float = 0f,
    /** Radians per second about the left axis, right-hand: + = nose going down. */
    val pitchRate: Float = 0f,
    /** Radians per second about up, right-hand: + = turning left. */
    val yawRate: Float = 0f
) {
    /**
     * The rider's two switches: nothing on the sensor can know how the bracket sits on the bike.
     * Each flips one axis and everything measured about or along it; the yaw rate is about up,
     * which neither switch touches.
     */
    fun oriented(flipNose: Boolean, flipSide: Boolean): Packet {
        val n = if (flipNose) -1f else 1f
        val s = if (flipSide) -1f else 1f
        return copy(
            leanDeg = leanDeg * s,
            pitchDeg = pitchDeg * n,
            longG = longG * n,
            latG = latG * s,
            rollRate = rollRate * s,
            pitchRate = pitchRate * n
        )
    }
}
