// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FlowTest {

    @Test
    fun wrapsTheChildThatDoesNotFit() {
        // 40 + 8 + 40 fits in 100; the third starts a second line under the taller of the first.
        val spots = flowSpots(intArrayOf(40, 40, 40), intArrayOf(10, 20, 10), maxWidth = 100, gap = 8)
        assertArrayEquals(intArrayOf(0, 0, 48, 0, 0, 28, 88, 38), spots)
    }

    @Test
    fun aChildWiderThanTheRowStillGetsItsOwnLine() {
        assertArrayEquals(intArrayOf(0, 0, 0, 18, 150, 28), flowSpots(intArrayOf(150, 30), intArrayOf(10, 10), 100, 8))
        assertArrayEquals(intArrayOf(0, 0), flowSpots(IntArray(0), IntArray(0), 100, 8))
    }
}
