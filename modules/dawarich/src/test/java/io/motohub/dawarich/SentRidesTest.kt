// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.dawarich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SentRidesTest {

    @Test
    fun remembersASentRideAcrossInstances() {
        val file = File.createTempFile("sent", ".properties").apply { delete() }
        try {
            assertNull(SentRides(file).get("ride-1"))
            SentRides(file).mark("ride-1", SentRide(1_700_000_000_000, 4321, "https://dawarich.example|x"))
            val back = SentRides(file).get("ride-1")!!
            assertEquals(1_700_000_000_000, back.atMillis)
            assertEquals(4321, back.points)
            // A '|' in the address survives: only the first two separate fields.
            assertEquals("https://dawarich.example|x", back.server)
            assertNull(SentRides(file).get("ride-2"))
        } finally {
            file.delete()
        }
    }
}
