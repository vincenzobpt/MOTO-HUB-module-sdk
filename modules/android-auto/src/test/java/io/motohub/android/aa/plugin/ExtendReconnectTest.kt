// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtendReconnectTest {
    @Test
    fun aDroppedSessionIsDialledAgainSoon() {
        assertEquals(3_000L, extendReconnectDelayMillis(failures = 0, sinceGoodMs = 0))
        assertEquals(3_000L, extendReconnectDelayMillis(failures = 1, sinceGoodMs = 10_000))
    }

    @Test
    fun repeatedFailuresWaitLonger() {
        assertEquals(10_000L, extendReconnectDelayMillis(failures = 2, sinceGoodMs = 30_000))
        assertEquals(10_000L, extendReconnectDelayMillis(failures = 50, sinceGoodMs = 60_000))
    }

    @Test
    fun aHeadUnitGoneForThreeMinutesEndsTheRun() {
        // E67E-6B89: the bike switched off must not leave the phone dialling it for the rest of the day.
        assertNull(extendReconnectDelayMillis(failures = 12, sinceGoodMs = EXTEND_GIVE_UP_MS))
        assertNull(extendReconnectDelayMillis(failures = 0, sinceGoodMs = EXTEND_GIVE_UP_MS + 1))
    }
}
