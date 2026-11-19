// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * A motion sensor fixed to the motorcycle, read by a module (contract 43).
 *
 * While a module streams through this capability, MOTO-HUB takes lean, g-forces and yaw rate from
 * it instead of from the phone: the trip's sensor log and the Ride Dashboard both prefer a sensor
 * bolted to the frame over a phone in a cradle. When the stream stops - the sensor is off, out of
 * range, or the module is removed - the app goes back to the phone's own sensors by itself.
 *
 * The module delivers what its sensor measures, in the motorcycle's frame; the app does the
 * physics (taking gravity out, turning the vector to the road, the kinematic cross-check), so every
 * source reaches a trip the same way.
 *
 * Answered as a capability. A module answers it only if it can stream.
 */
interface ModuleMotionSource {
    /**
     * Starts calling [listener] with every sample, on any thread the module likes, until
     * [removeMotionListener]. The listener returns fast and never throws back into the module.
     */
    fun addMotionListener(listener: ModuleMotionListener)

    fun removeMotionListener(listener: ModuleMotionListener)
}

fun interface ModuleMotionListener {
    fun onMotion(sample: ModuleMotionSample)
}

/**
 * One reading of a sensor fixed to the motorcycle.
 *
 * Axes are the motorcycle's, right-handed (ISO 8855): **x forward, y left, z up**, after whatever
 * the module does to undo the way its sensor is mounted. A module that cannot tell how the sensor
 * sits asks its rider and applies the answer before calling the listener.
 */
class ModuleMotionSample(
    /** When the sample was taken, on the [android.os.SystemClock.elapsedRealtimeNanos] clock. */
    val elapsedRealtimeNanos: Long,
    /** Degrees, + = leaning right, 0 with the motorcycle upright. */
    val leanDegrees: Float,
    /** Degrees, + = nose up, 0 with the motorcycle level. */
    val pitchDegrees: Float,
    /**
     * Specific force along x, y and z, in g: what an accelerometer reads, gravity included, so a
     * motorcycle standing upright reads 0, 0, +1.
     */
    val forwardG: Float,
    val leftG: Float,
    val upG: Float,
    /**
     * Angular rate about x, y and z, radians per second, right-hand rule. So: roll + = leaning
     * further right, pitch + = nose going DOWN (the opposite of [pitchDegrees]), yaw + = turning
     * left.
     */
    val rollRateRadiansPerSecond: Float,
    val pitchRateRadiansPerSecond: Float,
    val yawRateRadiansPerSecond: Float,
    /** Magnetic bearing of the motorcycle's nose, 0 to 360, or null when the sensor has none. */
    val magneticHeadingDegrees: Float? = null,
    /** True when the rider has zeroed the sensor on the motorcycle standing upright. */
    val zeroed: Boolean = false
)
