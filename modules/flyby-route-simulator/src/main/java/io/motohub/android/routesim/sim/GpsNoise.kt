// GPS error model for the route simulator: time-correlated position error, rare multipath
// wander bursts, and independently evolving satellite count and HDOP.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Position error to ADD to the true position, in metres (east, north), plus quality figures. */
class GpsFixError(val eastM: Double, val northM: Double, val accuracyM: Double, val satellites: Int, val hdop: Double)

class GpsNoise(private val rng: Random) {

    // Unit-variance AR(1) processes, scaled by the current sigma on output.
    private var zEast = rng.nextGaussian()
    private var zNorth = rng.nextGaussian()
    private var sigma = Double.NaN

    // Multipath wander burst: age < 0 means none active.
    private var wanderAgeS = -1.0
    private var wanderAmpM = 0.0
    private var wanderDirRad = 0.0

    private var satellites = SAT_MEAN + rng.nextInt(5) - 2
    private var hdop = (HDOP_MEAN + HDOP_SIGMA * rng.nextGaussian()).coerceIn(HDOP_MIN, HDOP_MAX)

    private var last: GpsFixError? = null

    fun step(dtS: Double, stationary: Boolean): GpsFixError {
        val sigmaTarget = if (stationary) SIGMA_STATIONARY_M else SIGMA_MOVING_M
        if (sigma.isNaN()) sigma = sigmaTarget
        if (!(dtS > 0.0)) {
            last?.let { return it }
            return compose(0.0, 0.0)
        }

        // Position error: exact AR(1) discretisation, independent east and north.
        val phi = exp(-dtS / POS_TAU_S)
        val innov = sqrt(1.0 - phi * phi)
        zEast = phi * zEast + innov * rng.nextGaussian()
        zNorth = phi * zNorth + innov * rng.nextGaussian()
        sigma += (sigmaTarget - sigma) * (1.0 - exp(-dtS / SIGMA_BLEND_TAU_S))

        // Wander bursts: Poisson arrivals, shaped like t*exp(-t/tau) so they build and fade slowly.
        var wanderE = 0.0
        var wanderN = 0.0
        if (wanderAgeS >= 0.0) {
            wanderAgeS += dtS
            if (wanderAgeS > WANDER_MAX_AGE_S) {
                wanderAgeS = -1.0
            } else {
                val x = wanderAgeS / WANDER_TAU_S
                val env = x * exp(1.0 - x) // peak 1.0 at x = 1
                wanderE = wanderAmpM * env * sin(wanderDirRad)
                wanderN = wanderAmpM * env * cos(wanderDirRad)
            }
        }
        val burstDraw = rng.nextDouble()
        val burstAmp = WANDER_MIN_M + (WANDER_MAX_M - WANDER_MIN_M) * rng.nextDouble()
        val burstDir = 2.0 * Math.PI * rng.nextDouble()
        if (wanderAgeS < 0.0 && burstDraw < 1.0 - exp(-dtS / WANDER_MEAN_INTERVAL_S)) {
            wanderAgeS = 0.0
            wanderAmpM = burstAmp
            wanderDirRad = burstDir
        }

        // Satellites: integer random walk with mean reversion, ~1 change per 4 s on average.
        val changeDraw = rng.nextDouble()
        val upDraw = rng.nextDouble()
        if (changeDraw < 1.0 - exp(-dtS / SAT_CHANGE_MEAN_S)) {
            val pUp = (0.5 + SAT_REVERT * (SAT_MEAN - satellites)).coerceIn(0.1, 0.9)
            satellites += if (upDraw < pUp) 1 else -1
            satellites = satellites.coerceIn(SAT_MIN, SAT_MAX)
        }

        // HDOP: own mean-reverting process; the mean leans slightly against the satellite count.
        val hdopMean = HDOP_MEAN - HDOP_SAT_COUPLING * (satellites - SAT_MEAN)
        val hPhi = exp(-dtS / HDOP_TAU_S)
        hdop = hdopMean + (hdop - hdopMean) * hPhi + HDOP_SIGMA * sqrt(1.0 - hPhi * hPhi) * rng.nextGaussian()
        hdop = hdop.coerceIn(HDOP_MIN, HDOP_MAX)

        val fix = compose(zEast * sigma + wanderE, zNorth * sigma + wanderN)
        last = fix
        return fix
    }

    private fun compose(east: Double, north: Double): GpsFixError {
        val accuracy = (hdop * ACCURACY_PER_HDOP).coerceIn(ACCURACY_MIN_M, ACCURACY_MAX_M)
        return GpsFixError(east, north, accuracy, satellites, hdop)
    }

    private companion object {
        const val POS_TAU_S = 25.0
        const val SIGMA_MOVING_M = 2.0
        const val SIGMA_STATIONARY_M = 1.2
        const val SIGMA_BLEND_TAU_S = 5.0

        const val WANDER_MEAN_INTERVAL_S = 600.0
        const val WANDER_MIN_M = 4.0
        const val WANDER_MAX_M = 8.0
        const val WANDER_TAU_S = 2.5
        const val WANDER_MAX_AGE_S = 25.0

        const val SAT_MIN = 8
        const val SAT_MAX = 16
        const val SAT_MEAN = 12
        const val SAT_CHANGE_MEAN_S = 4.0
        const val SAT_REVERT = 0.12

        const val HDOP_MIN = 0.6
        const val HDOP_MAX = 2.4
        const val HDOP_MEAN = 1.2
        const val HDOP_SIGMA = 0.3
        const val HDOP_TAU_S = 40.0
        const val HDOP_SAT_COUPLING = 0.04

        const val ACCURACY_PER_HDOP = 2.4
        const val ACCURACY_MIN_M = 1.5
        const val ACCURACY_MAX_M = 12.0
    }
}
