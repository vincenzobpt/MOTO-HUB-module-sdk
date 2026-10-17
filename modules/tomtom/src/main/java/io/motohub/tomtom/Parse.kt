// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import io.motohub.android.module.ModulePoiCategory
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

internal fun JSONObject?.text(key: String): String? {
    if (this == null || isNull(key)) return null
    val value = opt(key) as? String ?: return null
    return value.takeIf { it.isNotBlank() }
}

internal fun JSONObject?.number(key: String): Double? {
    if (this == null || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf { s -> s.isNotBlank() } }

/** A result of Suggest or Discover: a place, an address, a street, an area. */
class Hit(
    val id: String,
    val type: String,
    val title: String,
    /** Street, town, country: the lines under the title, joined. */
    val subtitle: String,
    val lat: Double?,
    val lon: Double?,
    val poiTypes: List<String>,
    val brand: String?,
    val tags: Map<String, String>
)

/** A traffic incident, in TomTom's categories. */
class Incident(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    /** TomTom's iconCategory: 1 accident, 6 jam, 7 lane closed, 8 road closed, 9 road works ... */
    val category: Int,
    /** 0 unknown, 1 minor, 2 moderate, 3 major, 4 undefined (closures). */
    val magnitude: Int,
    val delaySeconds: Int?,
    val lengthMeters: Double?,
    val from: String?,
    val to: String?,
    val roads: List<String>,
    val start: String?,
    val end: String?
)

/** What TomTom knows of the road at a point of the map. */
class Road(val name: String?, val ref: String?, val limitKmh: Int?)

/** Everything read out of TomTom's answers. Pure: the tests feed it the samples of the documentation. */
object Parse {

    /** The words TomTom itself gives for an error, or null. Its `code` is for logic, its message is for people. */
    fun errorWords(body: String): String? = runCatching {
        val o = JSONObject(body)
        val detailed = o.optJSONObject("detailedError")
        detailed.text("message") ?: o.text("errorText") ?: detailed.text("code")
    }.getOrNull()

    /** `results` of Suggest or Discover, as hits; entries without an id or a title are left out. */
    fun hits(json: String): List<Hit> {
        val results = JSONObject(json).optJSONArray("results").objects()
        return results.mapNotNull { r ->
            val title = r.text("title") ?: return@mapNotNull null
            val id = r.text("id") ?: return@mapNotNull null
            val coords = r.optJSONObject("position")?.optJSONArray("coordinates")
            val lon = coords?.optDouble(0, Double.NaN)?.takeIf { it.isFinite() }
            val lat = coords?.optDouble(1, Double.NaN)?.takeIf { it.isFinite() }
            val at = if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) lat to lon else null
            Hit(
                id = id,
                type = r.text("type") ?: "poi",
                title = title,
                subtitle = r.optJSONArray("subtitles").strings().joinToString(", "),
                lat = at?.first,
                lon = at?.second,
                poiTypes = r.optJSONArray("poiTypes").objects().mapNotNull { it.text("id") },
                brand = r.optJSONArray("poiBrands").objects().firstNotNullOfOrNull { it.text("name") },
                tags = tags(r)
            )
        }
    }

    /** The result of Details (one object, not a list) as OSM-style tags. */
    fun details(json: String): Map<String, String> = tags(JSONObject(json))

    /** OSM's words for what a place result holds. */
    fun tags(r: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val address = r.optJSONObject("address")
        address.text("street")?.let { out["addr:street"] = it }
        address.text("houseNumber")?.let { out["addr:housenumber"] = it }
        address.text("municipality")?.let { out["addr:city"] = it }
        address.text("postalCode")?.let { out["addr:postcode"] = it }
        r.optJSONArray("poiBrands").objects().firstNotNullOfOrNull { it.text("name") }?.let { out["brand"] = it }
        val contacts = r.optJSONArray("contacts").objects()
        contacts.firstNotNullOfOrNull { it.optJSONArray("phones").strings().firstOrNull() }?.let { out["phone"] = it }
        contacts.firstNotNullOfOrNull { it.optJSONArray("websites").strings().firstOrNull() }?.let { out["website"] = it }
        OpeningHours.osm(r.optJSONArray("openingHours"))?.let { out["opening_hours"] = it }
        return out
    }

    /** The place or address a reverse lookup found, as one line; null when there is none. */
    fun reverse(json: String): String? =
        JSONObject(json).optJSONArray("results").objects().firstNotNullOfOrNull { it.text("title") }

    /** `incidents` of Incident Details v5; entries without a position are left out. */
    fun incidents(json: String): List<Incident> {
        val list = JSONObject(json).optJSONArray("incidents").objects()
        return list.mapNotNull { f ->
            val p = f.optJSONObject("properties") ?: return@mapNotNull null
            val id = p.text("id") ?: return@mapNotNull null
            val at = middle(f.optJSONObject("geometry")) ?: return@mapNotNull null
            Incident(
                id = id,
                latitude = at.first,
                longitude = at.second,
                category = p.number("iconCategory")?.toInt() ?: 0,
                magnitude = p.number("magnitudeOfDelay")?.toInt() ?: 0,
                delaySeconds = p.number("delay")?.toInt()?.takeIf { it > 0 },
                lengthMeters = p.number("length")?.takeIf { it > 0 },
                from = p.text("from"),
                to = p.text("to"),
                roads = p.optJSONArray("roadNumbers").strings(),
                start = p.text("startTime"),
                end = p.text("endTime")
            )
        }
    }

    /** A point as (latitude, longitude); the middle vertex of a line. */
    private fun middle(geometry: JSONObject?): Pair<Double, Double>? {
        val c = geometry?.optJSONArray("coordinates") ?: return null
        val point = when (geometry.text("type")) {
            "LineString" -> c.optJSONArray(c.length() / 2)
            else -> c
        } ?: return null
        val lon = point.optDouble(0, Double.NaN)
        val lat = point.optDouble(1, Double.NaN)
        return if (lat.isFinite() && lon.isFinite()) lat to lon else null
    }

    /**
     * The road of the first reverse-geocoding answer. The limit comes as "50.00KPH" or "30.00MPH";
     * an answer with no street, number or limit is no road at all.
     */
    fun road(json: String): Road? {
        val address = JSONObject(json).optJSONArray("addresses").objects().firstOrNull()?.optJSONObject("address") ?: return null
        val name = address.text("streetName") ?: address.text("street")
        val ref = address.optJSONArray("routeNumbers").strings().firstOrNull()
        val kmh = address.text("speedLimit")?.let { limit ->
            val value = limit.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull()
            value?.let { if (limit.endsWith("MPH", ignoreCase = true)) (it * 1.609344).roundToInt() else it.roundToInt() }
        }?.takeIf { it > 0 }
        return if (name == null && ref == null && kmh == null) null else Road(name, ref, kmh)
    }

    /** Seconds of the first route of Calculate Route. */
    fun travelSeconds(json: String): Double? =
        JSONObject(json).optJSONArray("routes").objects().firstOrNull()
            ?.optJSONObject("summary")?.number("travelTimeInSeconds")
}

/**
 * The opening hours of TomTom (a date and its time ranges, for the coming days) in OSM's syntax.
 * shortcut: one date stands for its weekday, so a bank holiday among the days shown is read as
 * the usual hours of that weekday; upgrade by matching public holidays if riders report it.
 */
object OpeningHours {

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    fun osm(array: JSONArray?): String? {
        val byDay = arrayOfNulls<String>(7)
        for (entry in array.objects()) {
            val date = entry.text("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val day = date.dayOfWeek.value - 1
            if (byDay[day] != null) continue
            val ranges = entry.optJSONArray("timeRanges").objects().mapNotNull { r ->
                val start = r.text("start") ?: return@mapNotNull null
                val end = r.text("end") ?: return@mapNotNull null
                // Midnight at the end is the end of that day, 24:00 in OSM.
                start + "-" + if (end == "00:00" || end == "23:59") "24:00" else end
            }
            byDay[day] = if (ranges.isEmpty()) "off" else ranges.joinToString(",")
        }
        if (byDay.all { it == null }) return null
        if (byDay.all { it == "00:00-24:00" }) return "24/7"
        val parts = ArrayList<String>()
        var i = 0
        while (i < 7) {
            val hours = byDay[i]
            if (hours == null) { i++; continue }
            var j = i
            while (j + 1 < 7 && byDay[j + 1] == hours) j++
            parts.add((if (j > i) "${DAYS[i]}-${DAYS[j]}" else DAYS[i]) + " " + hours)
            i = j + 1
        }
        return parts.joinToString("; ")
    }
}

/**
 * TomTom's place types for the NAV's categories. The ids are those of TomTom's POI types page;
 * asking for a type also finds its subtypes, so a parent such as `hotel_or_motel` covers hotels
 * and motels. BAKERY has no id there, so it is left to the app's own sources.
 */
object Categories {

    private val TYPES: Map<String, List<String>> = mapOf(
        ModulePoiCategory.FUEL to listOf("fuel_station"),
        ModulePoiCategory.FOOD to listOf("restaurant"),
        ModulePoiCategory.CAFE to listOf("cafe", "coffee_shop"),
        ModulePoiCategory.BAR to listOf("bar", "pub"),
        ModulePoiCategory.PARKING to listOf("parking_facility"),
        ModulePoiCategory.REST_AREA to listOf("rest_area"),
        ModulePoiCategory.MOTORCYCLE_SERVICE to listOf("motorcycle_repair", "motorcycle_dealer"),
        ModulePoiCategory.CAR_SERVICE to listOf("car_repair_and_service"),
        ModulePoiCategory.CHARGING to listOf("charging_location"),
        ModulePoiCategory.HOTEL to listOf("hotel_or_motel"),
        ModulePoiCategory.CAMPSITE to listOf("camping_ground"),
        ModulePoiCategory.VIEWPOINT to listOf("viewpoint"),
        ModulePoiCategory.ATTRACTION to listOf("tourist_attraction"),
        ModulePoiCategory.SUPERMARKET to listOf("supermarket"),
        ModulePoiCategory.TOILETS to listOf("public_bathroom"),
        ModulePoiCategory.ATM to listOf("atm"),
        ModulePoiCategory.BANK to listOf("bank"),
        ModulePoiCategory.PHARMACY to listOf("pharmacy"),
        ModulePoiCategory.HOSPITAL to listOf("hospital"),
        ModulePoiCategory.POLICE to listOf("police_station"),
        ModulePoiCategory.POST_OFFICE to listOf("post_office"),
        ModulePoiCategory.SHOP to listOf("shop_or_store")
    )

    // Subtypes a result may carry instead of the type asked for.
    private val SUBTYPES: Map<String, String> = mapOf(
        "hotel" to ModulePoiCategory.HOTEL,
        "motel" to ModulePoiCategory.HOTEL,
        "caravan_site" to ModulePoiCategory.CAMPSITE,
        "recreational_camping_ground" to ModulePoiCategory.CAMPSITE,
        "open_car_parking_area" to ModulePoiCategory.PARKING,
        "parking_garage" to ModulePoiCategory.PARKING,
        "convenience_store" to ModulePoiCategory.SHOP
    )

    private val BY_TYPE: Map<String, String> =
        TYPES.flatMap { (category, ids) -> ids.map { it to category } }.toMap() + SUBTYPES

    fun supported(): Array<String> = TYPES.keys.toTypedArray()

    /** The TomTom type ids that cover [categories]; categories TomTom has no id for add none. */
    fun typesFor(categories: Collection<String>): List<String> = categories.flatMap { TYPES[it].orEmpty() }.distinct()

    /**
     * The category of a result with [typeIds], among those [wanted]. A result that carries none
     * of the ids is still the category asked for when only one was asked; otherwise null.
     */
    fun categoryOf(typeIds: List<String>, wanted: Collection<String>): String? {
        typeIds.firstNotNullOfOrNull { BY_TYPE[it]?.takeIf { c -> c in wanted } }?.let { return it }
        return wanted.singleOrNull()
    }
}
