// Tests for GpsNoise: error statistics, time correlation, quality figure ranges, dt independence, determinism.
package io.motohub.android.routesim.sim

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsNoiseTest {

    private class Run(val east: DoubleArray, val north: DoubleArray, val fixes: List<GpsFixError>)

    private fun run(seed: Long, dt: Double, steps: Int, stationary: Boolean): Run {
        val gps = GpsNoise(Random(seed))
        val east = DoubleArray(steps)
        val north = DoubleArray(steps)
        val fixes = ArrayList<GpsFixError>(steps)
        for (i in 0 until steps) {
            val f = gps.step(dt, stationary)
            east[i] = f.eastM
            north[i] = f.northM
            fixes.add(f)
        }
        return Run(east, north, fixes)
    }

    private fun pooledStd(r: Run): Double {
        val all = r.east + r.north
        val mean = all.average()
        return sqrt(all.sumOf { (it - mean) * (it - mean) } / all.size)
    }

    private fun autocorr(x: DoubleArray, lag: Int): Double {
        val mean = x.average()
        var num = 0.0
        var den = 0.0
        for (i in x.indices) den += (x[i] - mean) * (x[i] - mean)
        for (i in 0 until x.size - lag) num += (x[i] - mean) * (x[i + lag] - mean)
        return num / den
    }

    @Test
    fun movingErrorHasExpectedSpreadAndZeroMeanOverOneHourAt10Hz() {
        val r = run(seed = 1, dt = 0.1, steps = 36000, stationary = false)
        val std = pooledStd(r)
        assertTrue("std $std", std in 1.2..3.0)
        assertEquals(0.0, r.east.average(), 1.0)
        assertEquals(0.0, r.north.average(), 1.0)
    }

    @Test
    fun errorIsStronglyCorrelatedOverOneSecond() {
        val r = run(seed = 2, dt = 0.1, steps = 36000, stationary = false)
        val ac = autocorr(r.east, 10)
        assertTrue("east lag-1s autocorrelation $ac", ac > 0.9)
        assertTrue("north lag-1s autocorrelation ${autocorr(r.north, 10)}", autocorr(r.north, 10) > 0.9)
        // ...but decorrelates over minutes.
        assertTrue("lag-150s autocorrelation ${autocorr(r.east, 1500)}", autocorr(r.east, 1500) < 0.5)
    }

    @Test
    fun stationaryErrorIsSmallerThanMoving() {
        val still = pooledStd(run(seed = 3, dt = 0.1, steps = 36000, stationary = true))
        val moving = pooledStd(run(seed = 3, dt = 0.1, steps = 36000, stationary = false))
        assertTrue("stationary std $still", still in 0.7..1.8)
        assertTrue("stationary $still should be below moving $moving", moving > still * 1.15)
    }

    @Test
    fun statisticsHoldAtOtherSampleRates() {
        // 1 Hz for 10 h: same process, only the sampling differs.
        val r = run(seed = 4, dt = 1.0, steps = 36000, stationary = false)
        val std = pooledStd(r)
        assertTrue("std at 1 Hz $std", std in 1.2..3.0)
        assertTrue("lag-1 sample autocorrelation at 1 Hz ${autocorr(r.east, 1)}", autocorr(r.east, 1) > 0.9)
        // Very coarse steps stay finite and bounded.
        val coarse = run(seed = 4, dt = 30.0, steps = 2000, stationary = false)
        assertTrue(coarse.east.all { it.isFinite() && kotlin.math.abs(it) < 25.0 })
    }

    @Test
    fun qualityFiguresStayInRange() {
        val r = run(seed = 5, dt = 0.1, steps = 36000, stationary = false)
        for (f in r.fixes) {
            assertTrue("sats ${f.satellites}", f.satellites in 8..16)
            assertTrue("hdop ${f.hdop}", f.hdop >= 0.6 && f.hdop <= 2.4)
            assertTrue("accuracy ${f.accuracyM}", f.accuracyM >= 1.5 && f.accuracyM <= 12.0)
        }
        val sats = r.fixes.map { it.satellites }
        assertTrue("satellite count should vary", sats.toSet().size >= 4)
        assertTrue("mean sats ${sats.average()}", sats.average() in 10.0..14.0)
        val hdops = r.fixes.map { it.hdop }
        assertTrue("mean hdop ${hdops.average()}", hdops.average() in 0.9..1.6)
        assertTrue("hdop should vary", hdops.maxOrNull()!! - hdops.minOrNull()!! > 0.3)
    }

    @Test
    fun satelliteCountChangesAboutOncePerFourSeconds() {
        val r = run(seed = 6, dt = 0.1, steps = 36000, stationary = false)
        var changes = 0
        for (i in 1 until r.fixes.size) {
            val d = kotlin.math.abs(r.fixes[i].satellites - r.fixes[i - 1].satellites)
            assertTrue("jumped by $d", d <= 1)
            if (d != 0) changes++
        }
        assertTrue("changes in 1 h: $changes", changes in 600..1200)
    }

    @Test
    fun hdopIsOnlyMildlyAntiCorrelatedWithSatellites() {
        val r = run(seed = 7, dt = 0.5, steps = 40000, stationary = false)
        val s = r.fixes.map { it.satellites.toDouble() }
        val h = r.fixes.map { it.hdop }
        val ms = s.average()
        val mh = h.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in s.indices) {
            sxy += (s[i] - ms) * (h[i] - mh)
            sxx += (s[i] - ms) * (s[i] - ms)
            syy += (h[i] - mh) * (h[i] - mh)
        }
        val corr = sxy / sqrt(sxx * syy)
        assertTrue("correlation $corr should be mildly negative, not exact", corr < 0.1 && corr > -0.8)
    }

    @Test
    fun deterministicForSameSeedAndDifferentAcrossSeeds() {
        val a = run(seed = 42, dt = 0.1, steps = 3000, stationary = false)
        val b = run(seed = 42, dt = 0.1, steps = 3000, stationary = false)
        for (i in 0 until 3000) {
            assertEquals(a.east[i], b.east[i], 0.0)
            assertEquals(a.north[i], b.north[i], 0.0)
            assertEquals(a.fixes[i].satellites, b.fixes[i].satellites)
            assertEquals(a.fixes[i].hdop, b.fixes[i].hdop, 0.0)
            assertEquals(a.fixes[i].accuracyM, b.fixes[i].accuracyM, 0.0)
        }
        val c = run(seed = 43, dt = 0.1, steps = 3000, stationary = false)
        assertTrue("different seeds should differ", (0 until 3000).any { a.east[it] != c.east[it] })
    }

    @Test
    fun accuracyTracksHdop() {
        val r = run(seed = 8, dt = 0.1, steps = 5000, stationary = false)
        for (f in r.fixes) {
            assertEquals((f.hdop * 2.4).coerceIn(1.5, 12.0), f.accuracyM, 1e-9)
        }
    }
}
