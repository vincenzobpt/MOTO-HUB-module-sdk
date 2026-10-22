// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.mapopt

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class UsageTest {

    private var clock = Instant.parse("2026-10-31T23:59:59Z").toEpochMilli()

    @Test
    fun countsAndSurvivesARestart() {
        val file = File.createTempFile("mapopt-usage", ".properties").also { it.deleteOnExit() }
        val usage = Usage(file) { clock }
        repeat(3) { usage.add(Billed.MAP_LOADS) }
        assertEquals(3, usage.used(Billed.MAP_LOADS))
        assertEquals(3, Usage(file) { clock }.used(Billed.MAP_LOADS))
    }

    @Test
    fun aNewUtcMonthStartsAtZero() {
        val usage = Usage(null) { clock }
        usage.add(Billed.MAP_LOADS)
        clock = Instant.parse("2026-11-01T00:00:01Z").toEpochMilli()
        assertEquals(0, usage.used(Billed.MAP_LOADS))
    }
}
