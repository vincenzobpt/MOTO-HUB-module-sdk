// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadUnitServerBackoffTest {
    @Test
    fun theNormalPaceHoldsUntilAHandshakeFails() {
        assertEquals(1_500L, AaReceiver.headUnitServerBackoffMillis(0))
    }

    @Test
    fun consecutiveFailuresDoubleTheWaitUpToTwelveSeconds() {
        // WB-25: a server that accepts and never answers was dialled every 1.5s for minutes.
        assertEquals(
            listOf(3_000L, 6_000L, 12_000L, 12_000L, 12_000L),
            (1..5).map(AaReceiver::headUnitServerBackoffMillis)
        )
    }

    @Test
    fun aLongRunOfFailuresNeverOverflowsTheCap() {
        assertEquals(12_000L, AaReceiver.headUnitServerBackoffMillis(1_000))
    }
}
