// Lean-angle model for the route simulator: steady-state lean from speed and curvature,
// a first-order lag, a small correlated noise and the profile's lean cap.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.sqrt

/** Lean in degrees, +right. `signedCurvature` is 1/R in 1/m, + = turning right. */
class LeanModel(private val profile: DrivingProfile, private val rng: Random) {

    /** Noiseless, lagged lean in degrees (the physical state). */
    private var lean = 0.0

    /** Correlated noise state in degrees (stationary sigma [NOISE_SIGMA_DEG]). */
    private var noise = 0.0

    fun step(speedMs: Double, signedCurvature: Double, dtS: Double): Double {
        if (!(dtS > 0.0)) return output(speedMs)

        val v = if (speedMs.isFinite() && speedMs > 0.0) speedMs else 0.0
        val k = if (signedCurvature.isFinite()) signedCurvature else 0.0

        // Point-mass steady state, reduced by the rider's own body lean.
        val rawDeg = Math.toDegrees(atan(v * v * k / G))
        val fade = movingFactor(v)
        val target = rawDeg * RIDER_BODY_FACTOR * fade

        // Exact exponential update of a first-order lag, valid for any dt.
        lean += (target - lean) * (1.0 - exp(-dtS / LAG_TAU_S))

        // AR(1) noise with an exact discretisation; always drawn so the RNG stream is stable.
        val phi = exp(-dtS / NOISE_TAU_S)
        noise = phi * noise + NOISE_SIGMA_DEG * sqrt(1.0 - phi * phi) * rng.nextGaussian()

        return output(v)
    }

    private fun output(v: Double): Double {
        val cap = abs(profile.maxLeanDeg)
        val total = lean + noise * movingFactor(v)
        return total.coerceIn(-cap, cap)
    }

    /** 0 below ~3 km/h, rising smoothly to 1 at ~6 km/h. */
    private fun movingFactor(v: Double): Double {
        if (v <= STOPPED_MS) return 0.0
        if (v >= FULL_MS) return 1.0
        val t = (v - STOPPED_MS) / (FULL_MS - STOPPED_MS)
        return t * t * (3.0 - 2.0 * t)
    }

    private companion object {
        const val G = 9.81
        const val LAG_TAU_S = 0.25
        const val RIDER_BODY_FACTOR = 0.92
        const val NOISE_SIGMA_DEG = 0.4
        const val NOISE_TAU_S = 0.6
        const val STOPPED_MS = 3.0 / 3.6
        const val FULL_MS = 6.0 / 3.6
    }
}
