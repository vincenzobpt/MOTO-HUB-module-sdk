// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMappingTest {

    private fun config(marginWidth: Int, marginHeight: Int) =
        PhoneDiscovery.VideoConfig(
            index = 0, resolution = 2, frameRate = 0,
            marginWidth = marginWidth, marginHeight = marginHeight, density = 160
        )

    @Test
    fun aPanelTheSizeOfTheVisibleAreaIsOffsetByTheCroppedMargin() {
        // Vincenzo's unit, 2026-10-08: 1024x600 declared, 1280x720 cropped by 256x120.
        val m = AapPhoneSession.chooseTouchMapping(1024, 600, 1280, 720, config(256, 120))
        assertTrue(m.scaled)
        assertEquals(128, m.x(0))
        assertEquals(60, m.y(0))
        assertEquals(128 + 1023, m.x(1023))
        assertEquals(60 + 599, m.y(599))
    }

    @Test
    fun aSmallerPanelIsScaledOntoTheVisibleArea() {
        val m = AapPhoneSession.chooseTouchMapping(800, 480, 1280, 720, config(0, 0))
        assertTrue(m.scaled)
        assertEquals(0, m.x(0))
        assertEquals(640, m.x(400))
        assertEquals(360, m.y(240))
    }

    @Test
    fun aPanelTheSizeOfTheVideoIsReadAsVideoCoordinates() {
        val m = AapPhoneSession.chooseTouchMapping(1280, 720, 1280, 720, config(256, 120))
        assertFalse(m.scaled)
        assertEquals(500, m.x(500))
    }

    @Test
    fun aLargerPanelHasBeenMappedByTheUnitItself() {
        // A 2018 Uconnect: 1258x708 declared, 800x480 projected, x never past 791.
        val m = AapPhoneSession.chooseTouchMapping(1258, 708, 800, 480, config(0, 0))
        assertFalse(m.scaled)
        assertEquals(791, m.x(791))
    }

    @Test
    fun noDeclaredPanelMeansVideoCoordinates() {
        assertFalse(AapPhoneSession.chooseTouchMapping(0, 0, 1280, 720, config(256, 120)).scaled)
    }
}
