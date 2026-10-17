// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadAndLookTest {

    @Test
    fun asksOnlyWhenTheMapRestsCloseEnoughAndSomewhereNew() {
        val lookout = RoadLookout()
        // Too far out: the ring covers a neighbourhood.
        assertFalse(lookout.due(46.0, 10.0, 12.0))
        assertTrue(lookout.due(46.0, 10.0, 15.0))
        lookout.asked(46.0, 10.0)
        // 10 m away is the same road.
        assertFalse(lookout.due(46.0 + 10 / 111_320.0, 10.0, 15.0))
        assertTrue(lookout.due(46.0 + 30 / 111_320.0, 10.0, 15.0))
    }

    @Test
    fun aFailedLookIsAskedAgain() {
        val lookout = RoadLookout()
        lookout.asked(46.0, 10.0)
        assertFalse(lookout.due(46.0, 10.0, 15.0))
        lookout.forget()
        assertTrue(lookout.due(46.0, 10.0, 15.0))
    }

    @Test
    fun incidentsAreDrawnByTypeAndMagnitude() {
        assertEquals("🚧", IncidentLook.symbol(9))
        assertEquals("⛔", IncidentLook.symbol(8))
        assertEquals("🐢", IncidentLook.symbol(6))
        assertEquals("⚠️", IncidentLook.symbol(1))
        assertEquals("⚠️", IncidentLook.symbol(14))
        assertEquals(IncidentLook.AMBER, IncidentLook.color(1))
        assertEquals(IncidentLook.AMBER, IncidentLook.color(0))
        assertEquals(IncidentLook.RED, IncidentLook.color(2))
        assertEquals(IncidentLook.DEEP_RED, IncidentLook.color(3))
        assertEquals(IncidentLook.DEEP_RED, IncidentLook.color(4))
        assertEquals("+12 min", IncidentLook.label(725))
        assertEquals("+1 min", IncidentLook.label(20))
        assertEquals("", IncidentLook.label(null))
        assertEquals("", IncidentLook.label(0))
    }

    @Test
    fun wordsForLengthAndDelay() {
        assertEquals("3.4 km", Words.length(3400.0))
        assertEquals("240 m", Words.length(238.553))
        assertEquals("+12 min", Words.delay(725))
        assertEquals("+1 h 5 min", Words.delay(3900))
    }
}
