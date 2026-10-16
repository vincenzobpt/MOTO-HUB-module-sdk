// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

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
        assertEquals(listOf("near"), MarkerIndex(markers).near(45.0, 9.0, 10_000, 100).map { it.marker.id })
    }

    @Test
    fun photosOnTheSameSpotShareOnePin() {
        val markers = (1..10).map { Marker("p$it", 45.0 + it * 1e-6, 9.0, null, null) }
        val spots = MarkerIndex(markers).near(45.0, 9.0, 5_000, 100)
        assertEquals(1, spots.size)
        assertEquals("p1", spots[0].marker.id)
        assertEquals(10, spots[0].ids.size)
    }

    @Test
    fun neverMoreSpotsThanTheLimit() {
        val markers = (0 until 2_000).map { Marker("p$it", 45.0 + (it / 50) * 0.002, 9.0 + (it % 50) * 0.003, null, null) }
        val spots = MarkerIndex(markers).near(45.04, 9.075, 20_000, 300)
        assertTrue(spots.size <= 300)
        assertEquals(2_000, spots.sumOf { it.ids.size })
    }

    @Test
    fun indexGivesWhatAScanOfEveryMarkerGives() {
        val rnd = Random(42)
        // Clusters (several photos on one spot), spread, the antimeridian and the poles.
        val markers = (0 until 20_000).map {
            val lat: Double
            val lon: Double
            when (it % 4) {
                0 -> { lat = 45.0 + rnd.nextDouble(-0.5, 0.5); lon = 9.0 + rnd.nextDouble(-0.5, 0.5) }
                1 -> { lat = rnd.nextDouble(-89.9, 89.9); lon = rnd.nextDouble(-180.0, 180.0) }
                2 -> { lat = -17.0 + rnd.nextDouble(-1.0, 1.0); lon = rnd.nextDouble(0.0, 2.0).let { d -> if (rnd.nextBoolean()) 180.0 - d else -180.0 + d } }
                else -> { lat = 88.0 + rnd.nextDouble(0.0, 2.0); lon = rnd.nextDouble(-180.0, 180.0) }
            }
            Marker("p$it", lat, lon, null, null)
        }
        val index = MarkerIndex(markers)
        val centres = listOf(45.0 to 9.0, -17.0 to 179.9, -17.0 to -179.9, 89.5 to 0.0, -89.9 to 120.0, 0.0 to 0.0)
        for ((lat, lon) in centres) for (radius in listOf(500, 20_000, 300_000, 3_000_000)) for (limit in listOf(1, 50, 400)) {
            val expected = scan(markers, lat, lon, radius, limit)
            val got = index.near(lat, lon, radius, limit)
            assertEquals("$lat,$lon r=$radius", expected.map { it.marker.id }, got.map { it.marker.id })
            assertEquals("$lat,$lon r=$radius", expected.map { it.ids }, got.map { it.ids })
        }
        repeat(300) {
            val lat = rnd.nextDouble(-90.0, 90.0)
            val lon = rnd.nextDouble(-180.0, 180.0)
            val radius = rnd.nextInt(100, 2_000_000)
            val limit = rnd.nextInt(1, 500)
            assertEquals(scan(markers, lat, lon, radius, limit).map { it.ids }, index.near(lat, lon, radius, limit).map { it.ids })
        }
    }

    @Test
    fun photosAcrossTheAntimeridianAreNear() {
        val markers = listOf(Marker("east", -17.0, 179.99, null, null), Marker("west", -17.0, -179.99, null, null))
        val spots = MarkerIndex(markers).near(-17.0, 180.0, 5_000, 100)
        assertEquals(setOf("east", "west"), spots.flatMap { it.ids }.toSet())
    }

    @Test
    fun aPoleOrNonsenseDoesNotCrash() {
        val markers = listOf(Marker("pole", 90.0, 0.0, null, null), Marker("south", -90.0, 180.0, null, null))
        assertEquals(listOf("pole"), MarkerIndex(markers).near(89.999, 0.0, 1_000, 10).map { it.marker.id })
        assertTrue(MarkerIndex(markers).near(Double.NaN, 0.0, 1_000, 10).isEmpty())
        assertTrue(MarkerIndex(emptyList()).near(0.0, 0.0, 1_000, 10).isEmpty())
    }

    @Test
    fun mergeReplacesMovedPhotosAndAppendsNewOnes() {
        val held = listOf(Marker("a", 1.0, 1.0, null, null), Marker("b", 2.0, 2.0, null, null))
        assertTrue(Markers.merge(held, emptyList()) === held)
        assertTrue(Markers.merge(held, listOf(Marker("b", 2.0, 2.0, null, null))) === held)
        val merged = Markers.merge(held, listOf(Marker("c", 3.0, 3.0, "Roma", null), Marker("a", 5.0, 5.0, null, null)))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
        assertEquals(5.0, merged[0].latitude, 0.0)
    }

    @Test
    fun theCopyOnDiskReadsBackTheSame() {
        val markers = listOf(Marker("a1b2c3d4-0001", 45.5, 9.2, "Milano", "Italy"), Marker("a1b2c3d4-0002", -17.0, 179.5, null, null))
        val back = Markers.parse(Markers.toJson(markers))
        assertEquals(markers.map { listOf(it.id, it.latitude, it.longitude, it.city, it.country) }, back.map { listOf(it.id, it.latitude, it.longitude, it.city, it.country) })
    }

    /** What near() did before the index, the long way round included: every marker, every call. */
    private fun scan(markers: List<Marker>, latitude: Double, longitude: Double, radiusMeters: Int, limit: Int): List<Spot> {
        val m2d = 111_320.0
        val lonScale = m2d * cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        val r2 = radiusMeters.toDouble() * radiusMeters
        val cell = (2.0 * radiusMeters / sqrt(limit.toDouble())).coerceAtLeast(1.0)
        val cells = LinkedHashMap<Long, MutableList<Marker>>()
        for (m in markers) {
            var d = (m.longitude - longitude) % 360.0
            if (d > 180) d -= 360 else if (d < -180) d += 360
            val dx = d * lonScale
            val dy = (m.latitude - latitude) * m2d
            if (dx * dx + dy * dy > r2) continue
            val cx = floor(m.longitude * lonScale / cell).toLong()
            val cy = floor(m.latitude * m2d / cell).toLong()
            cells.getOrPut((cx shl 32) xor (cy and 0xFFFFFFFFL)) { ArrayList() }.add(m)
        }
        return cells.values.take(limit).map { group -> Spot(group[0], group.map { it.id }) }
    }
}
