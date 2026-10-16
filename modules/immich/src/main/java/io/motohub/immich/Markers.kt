// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** A photo with a position, as Immich's map gives it. */
class Marker(val id: String, val latitude: Double, val longitude: Double, val city: String?, val country: String?)

/** One pin on the map: the photo it shows, and every photo of its patch of ground (itself first). */
class Spot(val marker: Marker, val ids: List<String>)

object Markers {

    /** `GET /api/map/markers` as markers; entries without an id or a real position are left out. */
    fun parse(json: String): List<Marker> {
        val array = JSONArray(json)
        val out = ArrayList<Marker>(array.length())
        for (i in 0 until array.length()) {
            val m = array.optJSONObject(i) ?: continue
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: continue
            val lat = m.optDouble("lat", Double.NaN)
            val lon = m.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0)) continue
            out.add(Marker(id, lat, lon, m.text("city"), m.text("country")))
        }
        return out
    }

    /** The markers back in the shape [parse] reads, for the copy kept on disk. */
    fun toJson(markers: List<Marker>): String {
        val array = JSONArray()
        for (m in markers) {
            array.put(
                JSONObject().put("id", m.id).put("lat", m.latitude).put("lon", m.longitude)
                    .put("city", m.city ?: JSONObject.NULL).put("country", m.country ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    /**
     * [held] with [fresh] laid over it by id: a photo moved takes its new place, a new one goes at
     * the end so the pins already on the map keep their photo. [held] itself when nothing changed,
     * so whatever was worked out from it still holds.
     */
    fun merge(held: List<Marker>, fresh: List<Marker>): List<Marker> {
        if (fresh.isEmpty()) return held
        val byId = LinkedHashMap<String, Marker>(held.size + fresh.size)
        for (m in held) byId[m.id] = m
        var changed = byId.size != held.size
        for (m in fresh) {
            val old = byId.put(m.id, m)
            if (old == null || old.latitude != m.latitude || old.longitude != m.longitude || old.city != m.city || old.country != m.country) changed = true
        }
        return if (changed) ArrayList(byId.values) else held
    }
}

/**
 * The markers filed by a fixed grid of squares of [CELL] degrees, built once per set of markers:
 * a look around one place reads only the squares its circle covers, not every photo held.
 */
class MarkerIndex(private val markers: List<Marker>) {

    // Square number to the positions in [markers] of its photos, in list order.
    private val squares = HashMap<Int, IntArray>()

    init {
        val grouped = HashMap<Int, MutableList<Int>>()
        markers.forEachIndexed { i, m -> grouped.getOrPut(square(row(m.latitude), column(m.longitude))) { ArrayList() }.add(i) }
        for ((k, v) in grouped) squares[k] = v.toIntArray()
    }

    /**
     * The markers within [radiusMeters] of the centre, at most [limit] spots: the ground is cut into
     * a grid fine enough for [limit] cells, and each cell shows its first photo with the others
     * behind it. The grid is anchored to the globe, not to the centre, so panning keeps each pin
     * where it was instead of making them jump.
     */
    fun near(latitude: Double, longitude: Double, radiusMeters: Int, limit: Int): List<Spot> {
        if (limit <= 0 || radiusMeters < 0 || !latitude.isFinite() || !longitude.isFinite()) return emptyList()
        val lonScale = METERS_PER_DEGREE * cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        val radius = radiusMeters.toDouble()
        val found = candidates(latitude, longitude, radius / METERS_PER_DEGREE, radius / lonScale)
        // List order, as a scan of every marker would meet them: the same first photo per cell, the same cells kept.
        found.sort()
        val r2 = radius * radius
        val cell = (2.0 * radius / sqrt(limit.toDouble())).coerceAtLeast(1.0)
        val cells = LinkedHashMap<Long, MutableList<Marker>>()
        for (i in found) {
            val m = markers[i]
            val dx = wrap(m.longitude - longitude) * lonScale
            val dy = (m.latitude - latitude) * METERS_PER_DEGREE
            if (dx * dx + dy * dy > r2) continue
            val cx = floor(m.longitude * lonScale / cell).toLong()
            val cy = floor(m.latitude * METERS_PER_DEGREE / cell).toLong()
            cells.getOrPut((cx shl 32) xor (cy and 0xFFFFFFFFL)) { ArrayList() }.add(m)
        }
        return cells.values.take(limit).map { group -> Spot(group[0], group.map { it.id }) }
    }

    /** The positions of the markers in the squares that the box of ±[dLat], ±[dLon] degrees touches. */
    private fun candidates(latitude: Double, longitude: Double, dLat: Double, dLon: Double): IntArray {
        val rows = row(latitude - dLat)..row(latitude + dLat)
        val from = floor((longitude - dLon + 180.0) / CELL).toInt()
        val to = floor((longitude + dLon + 180.0) / CELL).toInt()
        // Near a pole, or zoomed out far enough, the box goes all the way round.
        val columns = if (to - from + 1 >= COLUMNS || dLon >= 180.0) 0 until COLUMNS else from..to
        val parts = ArrayList<IntArray>()
        val covered = (rows.last - rows.first + 1).toLong() * (columns.last - columns.first + 1)
        if (covered >= squares.size) {
            parts.addAll(squares.values)
        } else {
            for (r in rows) for (c in columns) squares[square(r, Math.floorMod(c, COLUMNS))]?.let { parts.add(it) }
        }
        val out = IntArray(parts.sumOf { it.size })
        var n = 0
        for (p in parts) {
            p.copyInto(out, n)
            n += p.size
        }
        return out
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
        const val CELL = 0.05
        const val ROWS = 3600
        const val COLUMNS = 7200

        fun row(latitude: Double) = floor((latitude.coerceIn(-90.0, 90.0) + 90.0) / CELL).toInt().coerceIn(0, ROWS - 1)
        fun column(longitude: Double) = Math.floorMod(floor((longitude + 180.0) / CELL).toInt(), COLUMNS)
        fun square(row: Int, column: Int) = row * COLUMNS + column

        /** A difference of longitudes the short way round: across the antimeridian too. */
        fun wrap(degrees: Double): Double {
            val d = degrees % 360.0
            return if (d > 180.0) d - 360.0 else if (d < -180.0) d + 360.0 else d
        }
    }
}
