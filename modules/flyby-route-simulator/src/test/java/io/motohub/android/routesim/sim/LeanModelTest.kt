// Tests for LeanModel: sign, magnitude, lag, cap, stopped behaviour, noise character, determinism.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun leanProfile(maxLeanDeg: Double = 38.0) = DrivingProfile(
    style = RideStyle.NORMAL,
    accelMs2 = 2.2,
    brakeMs2 = 3.0,
    lateralAccelMs2 = 3.2,
    overLimitFactor = 0.98,
    defaultCruiseKph = 80.0,
    maxLeanDeg = maxLeanDeg,
    idleRpm = 1300.0,
    maxRpm = 9500.0,
    shiftUpRpm = 6200.0,
    shiftDownRpm = 2800.0,
    gearRatios = doubleArrayOf(558.0, 410.0, 320.0, 262.0, 222.0, 190.0),
)

class LeanModelTest {

    private fun formulaDeg(v: Double, k: Double): Double =
        Math.toDegrees(atan(v * v * k / 9.81)) * 0.92

    @Test
    fun steadyStateMatchesFormulaWithSign() {
        val v = 20.0
        val k = 1.0 / 80.0
        val expected = formulaDeg(v, k)
        assertTrue("sanity: below cap", expected < 38.0)

        for (sign in doubleArrayOf(1.0, -1.0)) {
            val model = LeanModel(leanProfile(), Random(7))
            var sum = 0.0
            var n = 0
            for (i in 0 until 500) { // 5 s at 100 Hz
                val lean = model.step(v, sign * k, 0.01)
                if (i >= 400) { // last second, well past the lag
                    sum += lean
                    n++
                }
            }
            val mean = sum / n
            assertEquals(sign * expected, mean, 1.0)
            assertTrue("sign", mean * sign > 0.0)
        }
    }

    @Test
    fun stepResponseReaches63PercentAtTimeConstant() {
        val v = 20.0
        val k = 1.0 / 80.0
        val target = formulaDeg(v, k)

        // Fine steps.
        val fine = LeanModel(leanProfile(), Random(3))
        var lean = 0.0
        for (i in 0 until 25) lean = fine.step(v, k, 0.01)
        assertEquals(0.632 * target, lean, 1.5)

        // One coarse step must give the same answer: the update is exact for any dt.
        val coarse = LeanModel(leanProfile(), Random(3))
        val coarseLean = coarse.step(v, k, 0.25)
        assertEquals(0.632 * target, coarseLean, 1.5)
    }

    @Test
    fun capIsRespected() {
        val profile = leanProfile(maxLeanDeg = 38.0)
        val model = LeanModel(profile, Random(5))
        var maxAbs = 0.0
        var last = 0.0
        for (i in 0 until 300) {
            last = model.step(40.0, 1.0 / 20.0, 0.01) // would be ~76 deg uncapped
            maxAbs = maxOf(maxAbs, abs(last))
        }
        assertTrue("cap exceeded: $maxAbs", maxAbs <= 38.0 + 1e-9)
        assertEquals(38.0, last, 1e-9)

        val left = LeanModel(profile, Random(5))
        var minV = 0.0
        for (i in 0 until 300) minV = minOf(minV, left.step(40.0, -1.0 / 20.0, 0.01))
        assertTrue("left cap exceeded: $minV", minV >= -38.0 - 1e-9)
    }

    @Test
    fun zeroWhenStopped() {
        val model = LeanModel(leanProfile(), Random(9))
        for (i in 0 until 200) model.step(20.0, 1.0 / 80.0, 0.01) // leaned over
        var lean = 99.0
        for (i in 0 until 300) lean = model.step(0.0, 1.0 / 80.0, 0.01)
        assertEquals(0.0, lean, 0.01)

        // Crawling at 2 km/h in a tight turn stays upright too.
        val crawl = LeanModel(leanProfile(), Random(9))
        var c = 99.0
        for (i in 0 until 300) c = crawl.step(2.0 / 3.6, 1.0 / 5.0, 0.01)
        assertEquals(0.0, c, 0.01)
    }

    @Test
    fun noiseIsSmallAndCorrelatedNotWhite() {
        val model = LeanModel(leanProfile(), Random(21))
        val n = 5000
        val xs = DoubleArray(n)
        for (i in 0 until n) xs[i] = model.step(20.0, 0.0, 0.1) // straight line: pure noise
        val mean = xs.average()
        val variance = xs.sumOf { (it - mean) * (it - mean) } / n
        val sd = sqrt(variance)
        assertTrue("noise sd $sd", sd in 0.2..0.6)
        var cov = 0.0
        for (i in 0 until n - 1) cov += (xs[i] - mean) * (xs[i + 1] - mean)
        val lag1 = cov / (n * variance)
        assertTrue("lag-1 autocorrelation $lag1", lag1 > 0.6)
    }

    @Test
    fun deterministicForSameSeed() {
        val a = LeanModel(leanProfile(), Random(42))
        val b = LeanModel(leanProfile(), Random(42))
        for (i in 0 until 500) {
            val k = if (i % 200 < 100) 1.0 / 60.0 else -1.0 / 90.0
            assertEquals(a.step(18.0, k, 0.1), b.step(18.0, k, 0.1), 0.0)
        }
    }
}
