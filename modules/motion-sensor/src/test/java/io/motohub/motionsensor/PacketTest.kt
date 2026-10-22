// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.motionsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {

    private fun hex(s: String) = s.split("-").map { it.toInt(16).toByte() }.toByteArray()

    // Read off nRF Connect on the real sensor; the first characteristic said "42.4;0.16;0".
    private val leaningRight = hex("01-02-93-0C-A6-35-01-00-90-10-91-FA-9B-F1-A5-00-9A-FD-3B-03")

    @Test
    fun decodesTheRealSensor() {
        val p = Packet.decode(leaningRight)!!
        assertEquals(1, p.version)
        assertTrue(p.sensorOk)
        assertFalse(p.alert)
        assertEquals(3219, p.seq)
        assertEquals(79270L, p.sensorMillis)
        assertEquals(42.40f, p.leanDeg, 0.001f)
        // Sent as -13.91: + = nose down on the wire, + = nose up here.
        assertEquals(13.91f, p.pitchDeg, 0.001f)
        assertEquals(0.165f, p.longG, 0.0001f)
        assertEquals(-0.614f, p.latG, 0.0001f)
        assertEquals(0.827f, p.vertG, 0.0001f)
    }

    @Test
    fun rejectsShortOrUnknownPackets() {
        assertNull(Packet.decode(leaningRight.copyOf(19)))
        assertNull(Packet.decode(leaningRight.copyOf().also { it[0] = 2 }))
    }

    @Test
    fun orientationFlipsOnlyItsOwnAxes() {
        val p = Packet.decode(leaningRight)!!.oriented(flipNose = true, flipSide = false)
        assertEquals(-13.91f, p.pitchDeg, 0.001f)
        assertEquals(-0.165f, p.longG, 0.0001f)
        assertEquals(42.40f, p.leanDeg, 0.001f)
        val q = Packet.decode(leaningRight)!!.oriented(flipNose = false, flipSide = true)
        assertEquals(-42.40f, q.leanDeg, 0.001f)
        assertEquals(0.614f, q.latG, 0.0001f)
        assertEquals(0.827f, q.vertG, 0.0001f)
    }

    @Test
    fun calibrationTakesOutTheBoxTilt() {
        // The tester's box at rest (report D0B8-6068-B467): about -0.45 g sideways, bike upright.
        val atRest = Packet(1, false, true, false, 0, 0, 3f, 7.5f, 0f, -0.07f, -0.45f, 0.89f)
        val c = Calibration.of(atRest.latG, atRest.vertG, atRest.pitchDeg, 0)
        val p = atRest.calibrated(c)
        assertEquals(0f, p.latG, 0.0001f)
        assertEquals(0.997f, p.vertG, 0.001f)
        assertEquals(0f, p.pitchDeg, 0.0001f)
        // Lean and forward g are zeroed by the firmware, not here.
        assertEquals(3f, p.leanDeg, 0.0001f)
        assertEquals(-0.07f, p.longG, 0.0001f)
        assertEquals(p.latG, atRest.calibrated(null).calibrated(c).latG, 0.0001f)
    }

    @Test
    fun countsLossAcrossTheWrap() {
        assertEquals(0, Packet.lostBetween(10, 11))
        assertEquals(2, Packet.lostBetween(10, 13))
        assertEquals(1, Packet.lostBetween(65534, 0))
        // The ESP32 restarted: its counter is back near zero.
        assertEquals(0, Packet.lostBetween(21895, 3))
    }
}
