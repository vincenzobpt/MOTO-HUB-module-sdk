// Gearbox and engine model for the route simulator: gear from speed with hysteresis,
// smoothed rpm, upshift dips, downshift blips, idle and limiter behaviour.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Gear (0 = neutral/stopped, 1..N) and engine rpm at one instant. */
class DriveState(val gear: Int, val rpm: Double)

/** Gearbox + engine. `accelMs2` is the current longitudinal acceleration. */
class Drivetrain(private val profile: DrivingProfile, private val rng: Random) {

    private var gear = 0
    private var rpm = profile.idleRpm
    private var initialised = false

    /** Seconds since the last gear change (for the minimum shift interval). */
    private var sinceShift = 1.0e9

    /** Seconds since an upshift began, or negative when no dip is running. */
    private var dipT = -1.0

    /** Seconds since a downshift began, or negative when no blip is running. */
    private var blipT = -1.0

    fun step(speedMs: Double, accelMs2: Double, dtS: Double): DriveState {
        val ratios = profile.gearRatios
        val idle = profile.idleRpm
        val maxRpm = profile.maxRpm
        val nGears = ratios.size
        val v = if (speedMs.isFinite() && speedMs > 0.0) speedMs else 0.0
        val accel = if (accelMs2.isFinite()) accelMs2 else 0.0

        if (nGears == 0) return DriveState(0, idle)
        if (!(dtS > 0.0)) return DriveState(gear, rpm.coerceIn(idle, maxRpm))

        // Shift-bias: accelerating holds gears longer, braking drops them earlier.
        val accelFactor = (accel / ACCEL_REF_MS2).coerceIn(-1.0, 1.0)
        val upRpm = min(profile.shiftUpRpm * (1.0 + UP_BIAS * max(0.0, accelFactor)), maxRpm * UP_CAP_OF_MAX)
        val downRpm = profile.shiftDownRpm * (1.0 + DOWN_BIAS * max(0.0, -accelFactor))

        if (!initialised) {
            initialised = true
            gear = if (v < ENGAGE_MS) 0 else initialGear(v, upRpm)
            rpm = baseTarget(v)
        }

        // Advance running shift effects (a shift decided below starts at t = 0 this step).
        sinceShift += dtS
        if (dipT >= 0.0) {
            dipT += dtS
            if (dipT >= DIP_S) dipT = -1.0
        }
        if (blipT >= 0.0) {
            blipT += dtS
            if (blipT >= BLIP_S) blipT = -1.0
        }

        // Gear decision.
        if (gear == 0) {
            if (v >= ENGAGE_MS) {
                gear = 1
                sinceShift = 0.0
            }
        } else if (v < DISENGAGE_MS) {
            gear = 0
            sinceShift = 0.0
            dipT = -1.0
            blipT = -1.0
        } else if (sinceShift >= MIN_SHIFT_INTERVAL_S) {
            val here = v * ratios[gear - 1]
            if (gear < nGears && here > upRpm && v * ratios[gear] >= downRpm * UP_MARGIN) {
                gear++
                sinceShift = 0.0
                dipT = 0.0
                blipT = -1.0
            } else if (gear > 1 && here < downRpm && v * ratios[gear - 2] <= upRpm * DOWN_MARGIN) {
                gear--
                sinceShift = 0.0
                blipT = 0.0
                dipT = -1.0
            }
        }

        // Target rpm for the engaged gear, shaped by any shift event.
        var mult = 1.0
        if (dipT >= 0.0) mult *= DIP_FLOOR + (1.0 - DIP_FLOOR) * (dipT / DIP_S)
        if (blipT >= 0.0) mult *= 1.0 + BLIP_GAIN * (1.0 - blipT / BLIP_S)
        val target = min(baseTarget(v) * mult, maxRpm)

        // First-order response so the needle never jumps.
        rpm += (target - rpm) * (1.0 - exp(-dtS / RPM_TAU_S))
        rpm = rpm.coerceIn(idle, maxRpm)

        // Small throttle-dependent jitter on the reported value only.
        val throttle = (accel / max(profile.accelMs2, 0.5)).coerceIn(0.0, 1.0)
        val amp = NOISE_BASE + NOISE_THROTTLE * throttle
        val n = (amp * rng.nextGaussian()).coerceIn(-NOISE_MAX, NOISE_MAX)
        var out = rpm * (1.0 + n)
        if (out < idle) out = idle + (idle - out) // reflect instead of a flat floor at idle
        return DriveState(gear, out.coerceIn(idle, maxRpm))
    }

    /** Lowest gear whose rpm at [v] does not exceed [upRpm] (top gear if none). */
    private fun initialGear(v: Double, upRpm: Double): Int {
        val ratios = profile.gearRatios
        for (g in 1..ratios.size) {
            if (v * ratios[g - 1] <= upRpm) return g
        }
        return ratios.size
    }

    /** Engine rpm the current gear and speed ask for, before shift shaping. */
    private fun baseTarget(v: Double): Double {
        val idle = profile.idleRpm
        val ratios = profile.gearRatios
        if (gear == 0) {
            // Clutch slipping: rises smoothly from idle to what 1st gear would give at the engage speed.
            val slip = (v / ENGAGE_MS).coerceIn(0.0, 1.0)
            val first = max(idle, v * ratios[0])
            return idle + slip * (first - idle)
        }
        return max(idle, v * ratios[gear - 1])
    }

    private companion object {
        const val ENGAGE_MS = 2.0 / 3.6
        const val DISENGAGE_MS = 1.6 / 3.6
        const val MIN_SHIFT_INTERVAL_S = 0.6
        const val ACCEL_REF_MS2 = 3.0
        const val UP_BIAS = 0.06
        const val DOWN_BIAS = 0.25
        const val UP_CAP_OF_MAX = 0.97
        const val UP_MARGIN = 1.15
        const val DOWN_MARGIN = 0.92
        const val DIP_S = 0.3
        const val DIP_FLOOR = 0.65
        const val BLIP_S = 0.2
        const val BLIP_GAIN = 0.12
        const val RPM_TAU_S = 0.15
        const val NOISE_BASE = 0.004
        const val NOISE_THROTTLE = 0.006
        const val NOISE_MAX = 0.015
    }
}
