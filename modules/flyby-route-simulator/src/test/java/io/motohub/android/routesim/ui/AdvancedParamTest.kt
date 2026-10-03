// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import io.motohub.android.routesim.sim.DrivingProfile
import io.motohub.android.routesim.sim.RideStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedParamTest {
    private val normal = DrivingProfile.generic(RideStyle.NORMAL)

    @Test
    fun readsEachValueOfTheProfile() {
        assertEquals(2.2, AdvancedParam.ACCEL.read(normal), 1e-9)
        assertEquals(3.0, AdvancedParam.BRAKE.read(normal), 1e-9)
        assertEquals(3.2, AdvancedParam.LATERAL.read(normal), 1e-9)
        assertEquals(0.98, AdvancedParam.OVER_LIMIT.read(normal), 1e-9)
        assertEquals(38.0, AdvancedParam.MAX_LEAN.read(normal), 1e-9)
        assertEquals(9500.0, AdvancedParam.MAX_RPM.read(normal), 1e-9)
        assertEquals(6200.0, AdvancedParam.SHIFT_UP_RPM.read(normal), 1e-9)
    }

    @Test
    fun aStepMovesByTheStepWithoutFloatDrift() {
        var p = normal
        for (i in 1..10) p = AdvancedParam.ACCEL.nudge(p, 1)
        assertEquals(3.2, AdvancedParam.ACCEL.read(p), 1e-9)
        assertEquals(2.3, AdvancedParam.ACCEL.read(AdvancedParam.ACCEL.nudge(normal, 1)), 1e-9)
        assertEquals(0.99, AdvancedParam.OVER_LIMIT.read(AdvancedParam.OVER_LIMIT.nudge(normal, 1)), 1e-9)
        assertEquals(9600.0, AdvancedParam.MAX_RPM.read(AdvancedParam.MAX_RPM.nudge(normal, 1)), 1e-9)
    }

    @Test
    fun valuesStayInsideTheirBounds() {
        assertEquals(6.0, AdvancedParam.ACCEL.read(AdvancedParam.ACCEL.write(normal, 100.0)), 1e-9)
        assertEquals(0.5, AdvancedParam.ACCEL.read(AdvancedParam.ACCEL.write(normal, -3.0)), 1e-9)
        assertEquals(60.0, AdvancedParam.MAX_LEAN.read(AdvancedParam.MAX_LEAN.write(normal, 80.0)), 1e-9)
        assertEquals(1.4, AdvancedParam.OVER_LIMIT.read(AdvancedParam.OVER_LIMIT.write(normal, 9.0)), 1e-9)
    }

    @Test
    fun theRevLimiterStaysAboveTheShiftPoint() {
        val sporty = DrivingProfile.generic(RideStyle.SPORTY) // shifts up at 8000
        assertEquals(8500.0, AdvancedParam.MAX_RPM.read(AdvancedParam.MAX_RPM.write(sporty, 6000.0)), 1e-9)
        assertEquals(9000.0, AdvancedParam.SHIFT_UP_RPM.read(AdvancedParam.SHIFT_UP_RPM.write(normal, 13000.0)), 1e-9)
    }

    @Test
    fun knowsWhenItCannotStepFurther() {
        val top = AdvancedParam.ACCEL.write(normal, 6.0)
        assertFalse(AdvancedParam.ACCEL.canNudge(top, 1))
        assertTrue(AdvancedParam.ACCEL.canNudge(top, -1))
        val bottom = AdvancedParam.ACCEL.write(normal, 0.5)
        assertFalse(AdvancedParam.ACCEL.canNudge(bottom, -1))
        assertTrue(AdvancedParam.ACCEL.canNudge(normal, 1))
        assertTrue(AdvancedParam.ACCEL.canNudge(normal, -1))
    }

    @Test
    fun printsValuesWithTheirUnits() {
        assertEquals("2.2 m/s²", AdvancedParam.ACCEL.format(normal))
        assertEquals("98 %", AdvancedParam.OVER_LIMIT.format(normal))
        assertEquals("38°", AdvancedParam.MAX_LEAN.format(normal))
        assertEquals("9500 rpm", AdvancedParam.MAX_RPM.format(normal))
        assertEquals("6200 rpm", AdvancedParam.SHIFT_UP_RPM.format(normal))
    }

    @Test
    fun editingOneValueLeavesTheOthersAlone() {
        val edited = AdvancedParam.BRAKE.nudge(normal, 1)
        assertEquals(AdvancedParam.ACCEL.read(normal), AdvancedParam.ACCEL.read(edited), 1e-9)
        assertEquals(normal.style, edited.style)
        assertEquals(normal.idleRpm, edited.idleRpm, 1e-9)
    }

    @Test
    fun untouchedMeansEqualToTheStylesOwnValues() {
        assertTrue(AdvancedParam.isUntouched(normal))
        val edited = AdvancedParam.ACCEL.nudge(normal, 1)
        assertFalse(AdvancedParam.isUntouched(edited))
        assertTrue(AdvancedParam.isUntouched(AdvancedParam.ACCEL.nudge(edited, -1)))
        for (style in RideStyle.values()) assertTrue(AdvancedParam.isUntouched(DrivingProfile.generic(style)))
    }
}
