// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Properties

/** An album of the rider's, as `GET /api/albums` lists it. */
class Album(val id: String, val name: String, val count: Int?)

/**
 * Which of the held photos the map shows: favourites only, a stretch of time, one album.
 * Kept in config.properties with the rest.
 */
data class MapFilter(
    val favourites: Boolean = false,
    /** [ANY_TIME], [RECENT], or a year: "2024". */
    val period: String = ANY_TIME,
    val albumId: String? = null,
    val albumName: String? = null
) {

    val everything: Boolean get() = !favourites && period == ANY_TIME && albumId == null

    /** What a narrowed set of photos is filed under: whatever changes it, and not the album's name. */
    val key: String get() = "$favourites|$period|${albumId.orEmpty()}"

    /**
     * What to add to `GET /api/map/markers` for this filter, or null when only the album narrows.
     * Immich's fileCreatedAt is when the photo was taken, as the camera said.
     */
    fun markerQuery(now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val q = StringBuilder()
        if (favourites) q.append("&isFavorite=true")
        range(now, zone)?.let { (from, to) ->
            q.append("&fileCreatedAfter=").append(Instant.ofEpochMilli(from))
            if (to != null) q.append("&fileCreatedBefore=").append(Instant.ofEpochMilli(to))
        }
        return q.toString().ifEmpty { null }
    }

    /** From and, for a year, until when; null for any time. In the phone's own time zone. */
    internal fun range(now: Long, zone: ZoneId): Pair<Long, Long?>? {
        if (period == RECENT) return ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).minusMonths(12).toInstant().toEpochMilli() to null
        val year = period.toIntOrNull() ?: return null
        fun start(y: Int) = ZonedDateTime.of(y, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        return start(year) to start(year + 1)
    }

    fun store(p: Properties) {
        p.setProperty("mapFavourites", favourites.toString())
        p.setProperty("mapPeriod", period)
        albumId?.let {
            p.setProperty("mapAlbumId", it)
            p.setProperty("mapAlbumName", albumName.orEmpty())
        }
    }

    companion object {
        const val ANY_TIME = ""
        const val RECENT = "12m"
        val ALL = MapFilter()

        fun load(p: Properties) = MapFilter(
            favourites = p.getProperty("mapFavourites") == "true",
            period = p.getProperty("mapPeriod", ANY_TIME).takeIf { it == RECENT || it.toIntOrNull() != null } ?: ANY_TIME,
            albumId = p.getProperty("mapAlbumId")?.takeIf { it.isNotBlank() },
            albumName = p.getProperty("mapAlbumName")?.takeIf { it.isNotBlank() }
        )

        /** `GET /api/albums` as albums, by name. */
        fun albums(json: String): List<Album> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { i ->
                val a = array.optJSONObject(i) ?: return@mapNotNull null
                Album(a.text("id") ?: return@mapNotNull null, a.text("albumName") ?: "Untitled", a.number("assetCount")?.toInt())
            }.sortedBy { it.name.lowercase() }
        }

        /** `GET /api/timeline/buckets` ("2024-05-01" per month) as the years it holds, newest first. */
        fun years(json: String): List<Int> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.text("timeBucket")?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2999 }
            }.distinct().sortedBy { -it }
        }
    }
}
