// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.learn

import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WithLearnedTest {

    private fun learned(
        lean: Double? = 40.0, lateral: Double? = 4.0, accel: Double? = 2.6, brake: Double? = 3.6,
        over: Double? = 1.05, gears: DoubleArray? = doubleArrayOf(700.0, 520.0, 390.0, 290.0, 215.0, 160.0),
        idle: Double? = 1100.0, max: Double? = 10000.0, up: Double? = 6500.0, down: Double? = 3000.0,
    ) = LearnedProfile(0L, 5, 100.0, 3, lean, lateral, accel, brake, over, gears, idle, max, up, down)

    @Test
    fun nullLearnedLeavesTheProfileAlone() {
        val p = DrivingProfile.generic(RideStyle.SPORTY)
        assertSame(p, p.withLearned(null))
    }

    @Test
    fun aLearnedProfileWithNothingInItChangesNothing() {
        val p = DrivingProfile.generic(RideStyle.CALM)
        val q = p.withLearned(learned(null, null, null, null, null, null, null, null, null, null))
        assertEquals(p.accelMs2, q.accelMs2, 0.0)
        assertEquals(p.brakeMs2, q.brakeMs2, 0.0)
        assertEquals(p.lateralAccelMs2, q.lateralAccelMs2, 0.0)
        assertEquals(p.overLimitFactor, q.overLimitFactor, 0.0)
        assertEquals(p.maxLeanDeg, q.maxLeanDeg, 0.0)
        assertEquals(p.idleRpm, q.idleRpm, 0.0)
        assertEquals(p.maxRpm, q.maxRpm, 0.0)
        assertEquals(p.shiftUpRpm, q.shiftUpRpm, 0.0)
        assertEquals(p.shiftDownRpm, q.shiftDownRpm, 0.0)
        assertArrayEquals(p.gearRatios, q.gearRatios, 0.0)
        assertEquals(p.style, q.style)
        assertEquals(p.defaultCruiseKph, q.defaultCruiseKph, 0.0)
    }

    @Test
    fun aNullFieldKeepsTheCurrentValueWhileTheOthersAreApplied() {
        val p = DrivingProfile.generic(RideStyle.NORMAL).copy(brakeMs2 = 3.3)
        val q = p.withLearned(learned(brake = null, gears = null))
        assertEquals(3.3, q.brakeMs2, 0.0)
        assertArrayEquals(p.gearRatios, q.gearRatios, 0.0)
        assertEquals(2.6, q.accelMs2, 1e-9)
    }

    @Test
    fun normalTakesTheLearnedNumbersAsTheyAre() {
        val q = DrivingProfile.generic(RideStyle.NORMAL).withLearned(learned())
        assertEquals(2.6, q.accelMs2, 1e-9)
        assertEquals(3.6, q.brakeMs2, 1e-9)
        assertEquals(4.0, q.lateralAccelMs2, 1e-9)
        assertEquals(1.05, q.overLimitFactor, 1e-9)
        assertEquals(40.0, q.maxLeanDeg, 1e-9)
        assertEquals(6500.0, q.shiftUpRpm, 1e-9)
        assertEquals(3000.0, q.shiftDownRpm, 1e-9)
        assertEquals(1100.0, q.idleRpm, 1e-9)
        assertEquals(10000.0, q.maxRpm, 1e-9)
        assertArrayEquals(doubleArrayOf(700.0, 520.0, 390.0, 290.0, 215.0, 160.0), q.gearRatios, 0.0)
    }

    @Test
    fun calmAndSportyScaleByTheRatioOfTheBuiltInStyles() {
        val l = learned()
        val n = DrivingProfile.generic(RideStyle.NORMAL)
        for (style in listOf(RideStyle.CALM, RideStyle.SPORTY)) {
            val g = DrivingProfile.generic(style)
            val q = DrivingProfile.generic(style).withLearned(l)
            assertEquals(2.6 * g.accelMs2 / n.accelMs2, q.accelMs2, 1e-9)
            assertEquals(3.6 * g.brakeMs2 / n.brakeMs2, q.brakeMs2, 1e-9)
            assertEquals(4.0 * g.lateralAccelMs2 / n.lateralAccelMs2, q.lateralAccelMs2, 1e-9)
            assertEquals(1.05 * g.overLimitFactor / n.overLimitFactor, q.overLimitFactor, 1e-9)
            assertEquals(40.0 * g.maxLeanDeg / n.maxLeanDeg, q.maxLeanDeg, 1e-9)
            assertEquals(3000.0 * g.shiftDownRpm / n.shiftDownRpm, q.shiftDownRpm, 1e-9)
            assertEquals(style, q.style)
        }
        val calm = DrivingProfile.generic(RideStyle.CALM).withLearned(l)
        val normal = DrivingProfile.generic(RideStyle.NORMAL).withLearned(l)
        val sporty = DrivingProfile.generic(RideStyle.SPORTY).withLearned(l)
        assertTrue(calm.accelMs2 < normal.accelMs2 && normal.accelMs2 < sporty.accelMs2)
        assertTrue(calm.shiftUpRpm < normal.shiftUpRpm && normal.shiftUpRpm < sporty.shiftUpRpm)
        assertTrue(calm.maxLeanDeg < normal.maxLeanDeg && normal.maxLeanDeg < sporty.maxLeanDeg)
    }

    @Test
    fun resultsAreClampedToSaneRanges() {
        val wild = learned(lean = 99.0, lateral = 20.0, accel = 40.0, brake = 0.1, over = 3.0, idle = 50.0, max = 99999.0, up = 99999.0, down = 10.0)
        val q = DrivingProfile.generic(RideStyle.SPORTY).withLearned(wild)
        assertTrue(q.maxLeanDeg <= 62.0)
        assertTrue(q.lateralAccelMs2 <= 7.5)
        assertTrue(q.accelMs2 <= 5.5)
        assertTrue(q.brakeMs2 >= 1.2)
        assertTrue(q.overLimitFactor <= 1.4)
        assertEquals(700.0, q.idleRpm, 0.0)
        assertEquals(14000.0, q.maxRpm, 0.0)
        assertTrue(q.shiftDownRpm >= 1200.0)
        assertTrue(q.shiftUpRpm <= q.maxRpm * 0.95)
        assertTrue(q.shiftUpRpm >= q.shiftDownRpm + 1000.0)
    }

    @Test
    fun theUpshiftNeverEndsAboveTheLearnedRevLimit() {
        val q = DrivingProfile.generic(RideStyle.SPORTY).withLearned(learned(up = null, down = null, max = 7000.0))
        assertTrue("${q.shiftUpRpm}", q.shiftUpRpm <= 7000.0 * 0.95 + 1e-9)
    }

    @Test
    fun gearRatiosAreCopiedAndBadOnesIgnored() {
        val src = doubleArrayOf(700.0, 520.0, 390.0)
        val q = DrivingProfile.generic(RideStyle.NORMAL).withLearned(learned(gears = src))
        assertNotSame(src, q.gearRatios)
        src[0] = 1.0
        assertEquals(700.0, q.gearRatios[0], 0.0)

        val bad = DrivingProfile.generic(RideStyle.NORMAL).withLearned(learned(gears = doubleArrayOf(700.0, Double.NaN)))
        assertArrayEquals(DrivingProfile.GENERIC_GEAR_RATIOS, bad.gearRatios, 0.0)
    }
}
