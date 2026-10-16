// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSizeTest {

    @Test
    fun aFiftyMegapixelPhotoIsHalvedBelowTheLimit() {
        // 8160 / 2 = 4080: the old loop stopped at 1 and decoded a 200 MB bitmap.
        val sample = sampleFor(8160, 6120, 4096)
        assertEquals(2, sample)
        assertTrue(8160 / sample <= 4096)
    }

    @Test
    fun theLongSideNeverStaysOverTheLimit() {
        for ((w, h) in listOf(7008 to 4672, 4672 to 7008, 4097 to 3000, 12000 to 9000, 512 to 512, 4096 to 3072)) {
            val sample = sampleFor(w, h, 4096)
            assertTrue("$w x $h at $sample", maxOf(w, h) / sample <= 4096)
            // ...and is not shrunk more than it has to be.
            if (sample > 1) assertTrue("$w x $h at $sample", maxOf(w, h) / (sample / 2) > 4096)
        }
    }
}
