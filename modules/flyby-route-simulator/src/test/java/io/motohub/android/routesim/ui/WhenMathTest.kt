// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WhenMathTest {
    private val zone = ZoneId.of("Europe/Rome")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val base = at(2026, 10, 3, 10, 0)

    @Test
    fun formatsDateAndTime() {
        assertEquals("2026-10-03", WhenMath.formatDate(base, zone))
        assertEquals("10:00", WhenMath.formatTime(base, zone))
    }

    @Test
    fun stepsDaysKeepingTheTimeOfDay() {
        val next = WhenMath.shiftDays(base, zone, 1)
        assertEquals("2026-10-04", WhenMath.formatDate(next, zone))
        assertEquals("10:00", WhenMath.formatTime(next, zone))
        assertEquals("2026-09-26", WhenMath.formatDate(WhenMath.shiftDays(base, zone, -7), zone))
    }

    @Test
    fun aDayStepAcrossTheClockChangeKeepsTheWallClock() {
        // Clocks go back on 2026-10-25 in Rome: that day is 25 hours long.
        val before = at(2026, 10, 24, 10, 0)
        val after = WhenMath.shiftDays(before, zone, 1)
        assertEquals("2026-10-25", WhenMath.formatDate(after, zone))
        assertEquals("10:00", WhenMath.formatTime(after, zone))
        assertEquals(25 * 3_600_000L, after - before)
    }

    @Test
    fun stepsMinutesAndRollsOverMidnight() {
        assertEquals("10:15", WhenMath.formatTime(WhenMath.shiftMinutes(base, zone, 15), zone))
        assertEquals("09:45", WhenMath.formatTime(WhenMath.shiftMinutes(base, zone, -15), zone))
        val earlier = WhenMath.shiftMinutes(base, zone, -601)
        assertEquals("2026-10-02", WhenMath.formatDate(earlier, zone))
        assertEquals("23:59", WhenMath.formatTime(earlier, zone))
    }

    @Test
    fun parsesDatesInTheCommonOrders() {
        for (text in listOf("2026-10-05", "2026/10/5", "5.10.2026", "05-10-2026", " 2026-10-05 ")) {
            val parsed = WhenMath.parseDate(text, base, zone)
            assertNotNull(text, parsed)
            assertEquals(text, "2026-10-05", WhenMath.formatDate(parsed!!, zone))
            assertEquals(text, "10:00", WhenMath.formatTime(parsed, zone))
        }
    }

    @Test
    fun refusesDatesThatAreNotOne() {
        for (text in listOf("", "abc", "2026-13-01", "2026-02-30", "10/2026", "1-2-3", "2026-10", "1800-01-01", "2026-10-05-1")) {
            assertNull(text, WhenMath.parseDate(text, base, zone))
        }
    }

    @Test
    fun parsesTimes() {
        for ((text, expected) in listOf("9:30" to "09:30", "09.30" to "09:30", "930" to "09:30", "0930" to "09:30", "9" to "09:00", "23:59" to "23:59", "0:00" to "00:00")) {
            val parsed = WhenMath.parseTime(text, base, zone)
            assertNotNull(text, parsed)
            assertEquals(text, expected, WhenMath.formatTime(parsed!!, zone))
            assertEquals(text, "2026-10-03", WhenMath.formatDate(parsed, zone))
        }
    }

    @Test
    fun refusesTimesThatAreNotOne() {
        for (text in listOf("", "x", "24:00", "10:75", "12345", "1:2:3:4", "10:300")) {
            assertNull(text, WhenMath.parseTime(text, base, zone))
        }
    }

    @Test
    fun formatAndParseRoundTrip() {
        assertEquals(base, WhenMath.parseTime(WhenMath.formatTime(base, zone), base, zone))
        assertEquals(base, WhenMath.parseDate(WhenMath.formatDate(base, zone), base, zone))
    }
}
