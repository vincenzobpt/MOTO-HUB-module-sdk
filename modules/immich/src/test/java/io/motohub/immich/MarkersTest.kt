// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkersTest {

    @Test
    fun parseKeepsOnlyRealPositions() {
        val json = """[
            {"id":"a1b2c3d4-0001","lat":45.5,"lon":9.2,"city":"Milano","country":"Italy"},
            {"id":"a1b2c3d4-0002","lat":0,"lon":0,"city":null,"country":null},
            {"id":"","lat":45.5,"lon":9.2},
            {"id":"a1b2c3d4-0003","lat":46.0,"lon":11.0,"city":null}
        ]"""
        val markers = Markers.parse(json)
        assertEquals(listOf("a1b2c3d4-0001", "a1b2c3d4-0003"), markers.map { it.id })
        assertEquals("Milano", markers[0].city)
        assertNull(markers[1].city)
    }

    @Test
    fun nearLeavesOutWhatIsBeyondTheRadius() {
        val markers = listOf(Marker("near", 45.0, 9.0, null, null), Marker("far", 45.5, 9.0, null, null))
        // 0.5 degrees of latitude is about 55 km.
        assertEquals(listOf("near"), Markers.near(markers, 45.0, 9.0, 10_000, 100).map { it.marker.id })
    }

    @Test
    fun photosOnTheSameSpotShareOnePin() {
        val markers = (1..10).map { Marker("p$it", 45.0 + it * 1e-6, 9.0, null, null) }
        val spots = Markers.near(markers, 45.0, 9.0, 5_000, 100)
        assertEquals(1, spots.size)
        assertEquals("p1", spots[0].marker.id)
        assertEquals(10, spots[0].ids.size)
    }

    @Test
    fun neverMoreSpotsThanTheLimit() {
        val markers = (0 until 2_000).map { Marker("p$it", 45.0 + (it / 50) * 0.002, 9.0 + (it % 50) * 0.003, null, null) }
        val spots = Markers.near(markers, 45.04, 9.075, 20_000, 300)
        assertTrue(spots.size <= 300)
        assertEquals(2_000, spots.sumOf { it.ids.size })
    }
}
