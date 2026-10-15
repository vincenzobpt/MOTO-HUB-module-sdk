// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.json.JSONArray
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** A photo with a position, as Immich's map gives it. */
class Marker(val id: String, val latitude: Double, val longitude: Double, val city: String?, val country: String?)

/** One pin on the map: the photo it shows, and every photo of its patch of ground (itself first). */
class Spot(val marker: Marker, val ids: List<String>)

object Markers {

    private const val METERS_PER_DEGREE = 111_320.0

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

    /**
     * The markers within [radiusMeters] of the centre, at most [limit] spots: the ground is cut into
     * a grid fine enough for [limit] cells, and each cell shows its first photo with the others
     * behind it. The grid is anchored to the globe, not to the centre, so panning keeps each pin
     * where it was instead of making them jump.
     */
    fun near(markers: List<Marker>, latitude: Double, longitude: Double, radiusMeters: Int, limit: Int): List<Spot> {
        if (limit <= 0) return emptyList()
        val lonScale = METERS_PER_DEGREE * cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        val r2 = radiusMeters.toDouble() * radiusMeters
        val cell = (2.0 * radiusMeters / sqrt(limit.toDouble())).coerceAtLeast(1.0)
        val cells = LinkedHashMap<Long, MutableList<Marker>>()
        for (m in markers) {
            val dx = (m.longitude - longitude) * lonScale
            val dy = (m.latitude - latitude) * METERS_PER_DEGREE
            if (dx * dx + dy * dy > r2) continue
            val cx = floor(m.longitude * lonScale / cell).toLong()
            val cy = floor(m.latitude * METERS_PER_DEGREE / cell).toLong()
            cells.getOrPut((cx shl 32) xor (cy and 0xFFFFFFFFL)) { ArrayList() }.add(m)
        }
        return cells.values.take(limit).map { group -> Spot(group[0], group.map { it.id }) }
    }
}
