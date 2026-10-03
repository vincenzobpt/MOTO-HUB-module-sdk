// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.learn

import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import io.motohub.android.routesim.sim.TrafficLevel
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLearnerTest {

    private val generic = DrivingProfile.GENERIC_GEAR_RATIOS

    /** Two 12 km rides of one style on the winding road, light traffic, different seeds. */
    private fun rides(style: RideStyle, withRpm: Boolean, withLimit: Boolean): List<RideSample> =
        listOf(1L, 2L).map { seed ->
            LearnTestRides.sample(LearnTestRides.simulate(style, seed, 12_000.0, TrafficLevel.LIGHT), withRpm, withLimit)
        }

    private fun learn(style: RideStyle, withRpm: Boolean = true, withLimit: Boolean = true): LearnedProfile =
        ProfileLearner.learn(rides(style, withRpm, withLimit), 24.0, 1234L)

    @Test
    fun aSportyRiderLearnsMoreThanACalmOne() {
        val sporty = learn(RideStyle.SPORTY)
        val calm = learn(RideStyle.CALM)
        assertTrue("lean ${sporty.maxLeanDeg} vs ${calm.maxLeanDeg}", sporty.maxLeanDeg!! > calm.maxLeanDeg!!)
        assertTrue("lateral ${sporty.lateralAccelMs2} vs ${calm.lateralAccelMs2}", sporty.lateralAccelMs2!! > calm.lateralAccelMs2!!)
        assertTrue("accel ${sporty.accelMs2} vs ${calm.accelMs2}", sporty.accelMs2!! > calm.accelMs2!!)
        assertTrue("brake ${sporty.brakeMs2} vs ${calm.brakeMs2}", sporty.brakeMs2!! > calm.brakeMs2!!)
        assertTrue("over ${sporty.overLimitFactor} vs ${calm.overLimitFactor}", sporty.overLimitFactor!! > calm.overLimitFactor!!)
        assertTrue("shift ${sporty.shiftUpRpm} vs ${calm.shiftUpRpm}", sporty.shiftUpRpm!! > calm.shiftUpRpm!!)
    }

    @Test
    fun learnedNumbersLandNearTheProfileThatRodeTheRide() {
        val normal = learn(RideStyle.NORMAL)
        val g = DrivingProfile.generic(RideStyle.NORMAL)
        assertEquals(g.accelMs2, normal.accelMs2!!, g.accelMs2 * 0.5)
        assertEquals(g.brakeMs2, normal.brakeMs2!!, g.brakeMs2 * 0.5)
        assertEquals(g.lateralAccelMs2, normal.lateralAccelMs2!!, g.lateralAccelMs2 * 0.4)
        assertEquals(g.overLimitFactor, normal.overLimitFactor!!, 0.2)
        assertEquals(g.idleRpm, normal.idleRpm!!, g.idleRpm * 0.08)
        assertEquals(g.shiftUpRpm, normal.shiftUpRpm!!, g.shiftUpRpm * 0.2)
    }

    @Test
    fun gearRatiosComeBackWithinTenPercentOfTheBikeThatMadeThem() {
        for (style in RideStyle.values()) {
            val learned = learn(style)
            val ratios = learned.gearRatios
            assertNotNull("gears for $style", ratios)
            assertEquals(generic.size, ratios!!.size)
            for (g in generic.indices) {
                assertEquals("$style gear ${g + 1}", generic[g], ratios[g], generic[g] * 0.10)
            }
            for (g in 1 until ratios.size) assertTrue(ratios[g - 1] > ratios[g])
            assertEquals(2, learned.rpmRidesUsed)
            assertEquals(2, learned.ridesUsed)
            assertEquals(24.0, learned.kmUsed, 0.0)
            assertEquals(1234L, learned.learnedAtMillis)
        }
    }

    @Test
    fun withoutEngineDataTheEngineStaysUnlearnedButLeanIsLearned() {
        val p = learn(RideStyle.NORMAL, withRpm = false)
        assertEquals(0, p.rpmRidesUsed)
        assertNull(p.gearRatios)
        assertNull(p.idleRpm)
        assertNull(p.maxRpm)
        assertNull(p.shiftUpRpm)
        assertNull(p.shiftDownRpm)
        assertNotNull(p.maxLeanDeg)
        assertNotNull(p.lateralAccelMs2)
        assertNotNull(p.accelMs2)
        assertNotNull(p.brakeMs2)
    }

    @Test
    fun withoutLimitsTheOverLimitFactorIsNotLearned() {
        val p = learn(RideStyle.NORMAL, withLimit = false)
        assertNull(p.overLimitFactor)
        assertNotNull(p.maxLeanDeg)
    }

    @Test
    fun rpmRidesCountsOnlyRidesWithEnoughValidRpm() {
        val full = LearnTestRides.sample(LearnTestRides.simulate(RideStyle.NORMAL, 3L, 12_000.0, TrafficLevel.NONE), true, false)
        val none = LearnTestRides.sample(LearnTestRides.simulate(RideStyle.NORMAL, 4L, 12_000.0, TrafficLevel.NONE), false, false)
        val allNaN = RideSample(full.timesMillis, full.speedsKph, full.leanDegrees, FloatArray(full.speedsKph.size) { Float.NaN }, null)
        val p = ProfileLearner.learn(listOf(full, none, allNaN), 36.0, 0L)
        assertEquals(1, p.rpmRidesUsed)
        assertEquals(3, p.ridesUsed)
    }

    @Test
    fun theResultIsDeterministic() {
        val a = learn(RideStyle.NORMAL)
        val b = learn(RideStyle.NORMAL)
        assertEquals(a.maxLeanDeg, b.maxLeanDeg)
        assertEquals(a.accelMs2, b.accelMs2)
        assertEquals(a.shiftUpRpm, b.shiftUpRpm)
        assertTrue(a.gearRatios!!.contentEquals(b.gearRatios!!))
    }

    @Test
    fun tooLittleDataLearnsNothing() {
        val n = 100
        val tiny = RideSample(
            LongArray(n) { it * 100L }, FloatArray(n) { 60f }, FloatArray(n) { 10f },
            FloatArray(n) { 4000f }, FloatArray(n) { 50f },
        )
        val p = ProfileLearner.learn(listOf(tiny), 1.0, 0L)
        assertFalse(p.hasAnything)
    }

    @Test
    fun leanIgnoresNaNAndImpossibleValuesAndStandstill() {
        val n = 2000
        val lean = FloatArray(n) { if (it % 2 == 0) 20f else -20f }
        lean[10] = Float.NaN
        lean[11] = 80f // impossible, ignored
        lean[12] = -170f
        val speeds = FloatArray(n) { 60f }
        for (i in 100 until 600) { speeds[i] = 0f; lean[i] = 50f } // stopped: not counted
        val sample = RideSample(LongArray(n) { it * 1000L }, speeds, lean, null, null)
        val p = ProfileLearner.learn(listOf(sample), 5.0, 0L)
        assertEquals(20.0, p.maxLeanDeg!!, 1e-6)
        assertEquals(9.81 * Math.tan(Math.toRadians(20.0 / 0.92)), p.lateralAccelMs2!!, 1e-6)
    }

    @Test
    fun accelAndBrakeUseTheNinetiethPercentileOfPairsWithinTheTimeAndSpeedLimits() {
        // 100 ms apart: 40 steps of +4 m/s^2 then 32 steps of -5 m/s^2, repeated, so the speed
        // stays between 50 and ~108 km/h. Every positive step is 4.0, every negative one 5.0.
        val n = 1800
        val speeds = FloatArray(n)
        speeds[0] = 50f
        for (i in 1 until n) {
            val up = (i - 1) % 72 < 40
            speeds[i] = if (up) speeds[i - 1] + 4f * 0.36f else speeds[i - 1] - 5f * 0.36f
        }
        val s = RideSample(LongArray(n) { it * 100L }, speeds, FloatArray(n) { 0f }, null, null)
        val p = ProfileLearner.learn(listOf(s), 1.0, 0L)
        assertEquals(4.0, p.accelMs2!!, 1e-3)
        assertEquals(5.0, p.brakeMs2!!, 1e-3)
    }

    @Test
    fun pairsFurtherApartThanTwoAndAHalfSecondsAreIgnored() {
        val n = 600
        val speeds = FloatArray(n) { 30f + 25f * (it % 2) }
        val gap = RideSample(LongArray(n) { it * 3000L }, speeds, FloatArray(n) { 0f }, null, null)
        val p = ProfileLearner.learn(listOf(gap), 1.0, 0L)
        assertNull(p.accelMs2)
        assertNull(p.brakeMs2)
    }

    @Test
    fun overLimitFactorIsTheMedianOfSpeedOverLimit() {
        val n = 1000
        val speeds = FloatArray(n) { 55f }
        val limits = FloatArray(n) { 50f } // 1.1
        for (i in 0 until 100) limits[i] = Float.NaN // unknown: skipped
        for (i in 100 until 200) speeds[i] = 10f // below half the limit: skipped
        val s = RideSample(LongArray(n) { it * 1000L }, speeds, FloatArray(n) { 0f }, null, limits)
        val p = ProfileLearner.learn(listOf(s), 1.0, 0L)
        assertEquals(1.1, p.overLimitFactor!!, 1e-6)
    }

    @Test
    fun percentileInterpolates() {
        val v = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, ProfileLearner.percentile(v, 0.0), 1e-9)
        assertEquals(3.0, ProfileLearner.percentile(v, 50.0), 1e-9)
        assertEquals(4.6, ProfileLearner.percentile(v, 90.0), 1e-9)
        assertEquals(5.0, ProfileLearner.percentile(v, 100.0), 1e-9)
        assertTrue(ProfileLearner.percentile(DoubleArray(0), 50.0).isNaN())
    }

    @Test
    fun kMeansFindsSeparatedClusters() {
        val x = DoubleArray(300) { (if (it % 3 == 0) 1.0 else if (it % 3 == 1) 2.0 else 3.0) + 0.01 * (it % 5) }
        val c = ProfileLearner.kMeans(x, doubleArrayOf(0.5, 2.4, 3.5))
        assertEquals(1.02, c[0], 0.01)
        assertEquals(2.02, c[1], 0.01)
        assertEquals(3.02, c[2], 0.01)
        assertTrue(abs(c[2] - c[1]) > 0.9)
    }
}
