// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class UsageTest {

    private var clock = Instant.parse("2026-10-31T23:59:59Z").toEpochMilli()
    private fun file(): File = File.createTempFile("tomtom-usage", ".properties").also { it.deleteOnExit() }

    @Test
    fun countsPerApiAndStopsAtTheAllowance() {
        val usage = Usage(null) { clock }
        assertTrue(usage.allowed(Api.INCIDENTS))
        usage.add(Api.INCIDENTS, Api.INCIDENTS.allowance - 1)
        assertTrue(usage.allowed(Api.INCIDENTS))
        assertEquals(1, usage.room(Api.INCIDENTS, 5))
        usage.add(Api.INCIDENTS)
        assertFalse(usage.allowed(Api.INCIDENTS))
        assertEquals(0, usage.room(Api.INCIDENTS, 5))
        // Another API has its own allowance.
        assertTrue(usage.allowed(Api.ROUTING))
    }

    @Test
    fun aNewUtcMonthStartsAtZero() {
        val usage = Usage(null) { clock }
        usage.add(Api.INCIDENTS, Api.INCIDENTS.allowance)
        assertFalse(usage.allowed(Api.INCIDENTS))
        assertEquals("2026-10", usage.month())
        clock = Instant.parse("2026-11-01T00:00:01Z").toEpochMilli()
        assertEquals("2026-11", usage.month())
        assertTrue(usage.allowed(Api.INCIDENTS))
        assertEquals(0, usage.used(Api.INCIDENTS))
    }

    @Test
    fun keepsTheCountOnDiskButNotLastMonths() {
        val f = file()
        Usage(f) { clock }.add(Api.DISCOVER, 7)
        assertEquals(7, Usage(f) { clock }.used(Api.DISCOVER))
        clock = Instant.parse("2026-11-01T00:00:01Z").toEpochMilli()
        val november = Usage(f) { clock }
        assertEquals(0, november.used(Api.DISCOVER))
        november.add(Api.DISCOVER)
        assertFalse(f.readText().contains("2026-10"))
    }
}
