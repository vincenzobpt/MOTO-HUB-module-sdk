// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties

class MapFilterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()

    @Test
    fun everythingAsksNothingMore() {
        assertTrue(MapFilter.ALL.everything)
        assertNull(MapFilter.ALL.markerQuery(now, ZoneOffset.UTC))
        // Only the album narrows: that is a question for the album, not the markers.
        assertNull(MapFilter(albumId = "a1b2c3d4-0001").markerQuery(now, ZoneOffset.UTC))
    }

    @Test
    fun aYearIsFromItsFirstDayToTheNextOne() {
        val q = MapFilter(favourites = true, period = "2024").markerQuery(now, ZoneOffset.UTC)
        assertEquals("&isFavorite=true&fileCreatedAfter=2024-01-01T00:00:00Z&fileCreatedBefore=2025-01-01T00:00:00Z", q)
    }

    @Test
    fun theLastTwelveMonthsHaveNoEnd() {
        assertEquals("&fileCreatedAfter=2025-10-15T12:00:00Z", MapFilter(period = MapFilter.RECENT).markerQuery(now, ZoneOffset.UTC))
    }

    @Test
    fun keptAndReadBack() {
        val f = MapFilter(favourites = true, period = "2023", albumId = "a1b2c3d4-0001", albumName = "Alps 2023")
        val p = Properties()
        f.store(p)
        assertEquals(f, MapFilter.load(p))
        assertEquals(MapFilter.ALL, MapFilter.load(Properties()))
        // The album's name is not part of what the photos are filed under.
        assertEquals(f.key, f.copy(albumName = "Renamed").key)
        assertFalse(f.key == f.copy(period = "2022").key)
    }

    @Test
    fun albumsAndYearsAsTheServerListsThem() {
        val albums = MapFilter.albums("""[{"id":"b2","albumName":"zeta","assetCount":3},{"id":"a1","albumName":"Alps","assetCount":120},{"albumName":"no id"}]""")
        assertEquals(listOf("Alps", "zeta"), albums.map { it.name })
        assertEquals(120, albums[0].count)
        val years = MapFilter.years("""[{"timeBucket":"2024-05-01","count":3},{"timeBucket":"2026-01-01","count":1},{"timeBucket":"2024-01-01","count":9}]""")
        assertEquals(listOf(2026, 2024), years)
    }

    @Test
    fun theCacheDropsWhatWasReadLongestAgo() {
        val dir = folder.newFolder("images")
        val sizes = File(dir, "thumbnail").apply { mkdirs() }
        val files = (0 until 10).map { i ->
            File(sizes, "p$i.img").apply { writeBytes(ByteArray(100)); setLastModified(1_000_000L + i * 1000) }
        }
        // Read a moment ago: kept, though written first.
        files[0].setLastModified(2_000_000L)
        val writing = File(sizes, "p99.tmp").apply { writeBytes(ByteArray(500)); setLastModified(1L) }
        trimCache(dir, 600)
        assertTrue(files[0].exists())
        assertFalse(files[1].exists())
        assertTrue(files[9].exists())
        assertTrue(writing.exists())
        assertTrue(files.filter { it.exists() }.sumOf { it.length() } <= 540)
    }
}
