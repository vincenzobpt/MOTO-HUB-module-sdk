// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.dawarich

import org.junit.Assert.assertEquals
import org.junit.Test

class OutcomeTest {

    @Test
    fun aWrongAddressKeepsThePoints() {
        // "https://host/api/v1" typed as the address: every request answers 404.
        assertEquals(Tracker.Outcome.RETRY, outcomeOf(404))
    }

    @Test
    fun onlyAnAnswerAboutTheBatchDropsIt() {
        listOf(400, 413, 422).forEach { assertEquals("$it", Tracker.Outcome.REJECTED, outcomeOf(it)) }
        listOf(401, 403, 405, 408, 429, 500, 502, 503).forEach { assertEquals("$it", Tracker.Outcome.RETRY, outcomeOf(it)) }
        listOf(200, 201, 204).forEach { assertEquals("$it", Tracker.Outcome.OK, outcomeOf(it)) }
    }
}
