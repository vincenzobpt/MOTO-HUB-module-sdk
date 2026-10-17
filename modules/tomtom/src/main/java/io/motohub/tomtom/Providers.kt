// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import io.motohub.android.module.ModuleGeocodeResult
import io.motohub.android.module.ModuleGeocoder
import io.motohub.android.module.ModuleMapLayer
import io.motohub.android.module.ModuleMapLayerPin
import io.motohub.android.module.ModuleMapOverlay
import io.motohub.android.module.ModuleOverlayTiles
import io.motohub.android.module.ModulePlaceProvider
import io.motohub.android.module.ModulePoi
import io.motohub.android.module.ModulePoiAnswer
import io.motohub.android.module.ModuleTravelTimes
import io.motohub.android.module.MotoHubModuleHost
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Each capability is always offered and answers "nothing" when the module is not set up, the
 * feature is switched off or the month's allowance is used up, so the app never has to know.
 * Everything kept here is in memory, for minutes: TomTom's terms forbid storing its results.
 */

/** Traffic flow as raster tiles over the map. Tiles are counted by TomTom, not by this phone. */
internal class TrafficOverlay(private val service: TomTomService) : ModuleMapOverlay {

    override val title = "Traffic"
    override val attribution = "© TomTom"

    override fun tiles(dark: Boolean): Array<ModuleOverlayTiles> =
        if (!service.on(Feature.TRAFFIC)) emptyArray()
        else arrayOf(ModuleOverlayTiles(service.flowTiles(dark), 256, 0.85f, 6, 22))
}

/** How an incident is drawn as a pin and told in words: pure, so the tests can pin it down. */
internal object IncidentLook {
    const val AMBER = 0xFFFFC94D.toInt()
    const val RED = 0xFFFF6B6B.toInt()
    const val DEEP_RED = 0xFFB3261E.toInt()

    /** 8 road closed, 7 lane closed: ⛔; 9 road works: 🚧; 6 jam: 🐢; the rest (accident, weather, hazard): ⚠️. */
    fun symbol(category: Int): String = when (category) {
        8, 7 -> "⛔"
        9 -> "🚧"
        6 -> "🐢"
        else -> "⚠️"
    }

    fun title(category: Int): String = when (category) {
        1 -> "Accident"
        2 -> "Fog"
        3 -> "Dangerous conditions"
        4 -> "Rain"
        5 -> "Ice"
        6 -> "Traffic jam"
        7 -> "Lane closed"
        8 -> "Road closed"
        9 -> "Road works"
        10 -> "Wind"
        11 -> "Flooding"
        14 -> "Broken-down vehicle"
        else -> "Traffic incident"
    }

    /** Minor and unknown are amber, moderate red, major and the undefined of a closure deep red. */
    fun color(magnitude: Int): Int = when (magnitude) {
        2 -> RED
        3, 4 -> DEEP_RED
        else -> AMBER
    }

    fun magnitudeWords(magnitude: Int): String? = when (magnitude) {
        1 -> "Minor"
        2 -> "Moderate"
        3 -> "Major"
        4 -> "Closure"
        else -> null
    }

    /** "+12 min" when the delay is known, else nothing. */
    fun label(delaySeconds: Int?): String =
        if (delaySeconds == null || delaySeconds <= 0) "" else "+" + maxOf(1, (delaySeconds + 30) / 60) + " min"
}

/** The incidents as pins on the NAV map; a tap opens the incident's page. */
internal class IncidentLayer(private val host: MotoHubModuleHost, private val service: TomTomService) : ModuleMapLayer {

    override val title = "Traffic"

    // One cell of the grid and what TomTom said about it, until it expires.
    private class Cell(val until: Long, val incidents: List<Incident>)

    private val cells = HashMap<Long, Cell>()
    @Volatile private var known: Map<String, Incident> = emptyMap()

    /** The incident whose page is open, or about to be. */
    @Volatile var open: Incident? = null

    override fun pinsNear(latitude: Double, longitude: Double, radiusMeters: Int, limit: Int): Array<ModuleMapLayerPin> {
        if (!service.on(Feature.TRAFFIC) || radiusMeters > MAX_RADIUS) return emptyArray()
        val now = System.currentTimeMillis()
        val dLat = radiusMeters / METERS_PER_DEGREE
        val dLon = radiusMeters / (METERS_PER_DEGREE * maxOf(0.2, cos(Math.toRadians(latitude))))
        val seen = HashMap<String, Incident>()
        for (row in floor((latitude - dLat) / CELL).toInt()..floor((latitude + dLat) / CELL).toInt()) {
            for (col in floor((longitude - dLon) / CELL).toInt()..floor((longitude + dLon) / CELL).toInt()) {
                for (i in cell(row, col, now)) seen[i.id] = i
            }
        }
        val near = seen.values.map { it to meters(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= radiusMeters }.sortedBy { it.second }.take(limit).map { it.first }
        known = near.associateBy { it.id }
        return near.map {
            ModuleMapLayerPin(it.id, it.latitude, it.longitude, IncidentLook.color(it.magnitude), IncidentLook.symbol(it.category), IncidentLook.label(it.delaySeconds))
        }.toTypedArray()
    }

    private fun cell(row: Int, col: Int, now: Long): List<Incident> {
        val key = row.toLong() * 100_000L + col
        synchronized(cells) {
            cells[key]?.takeIf { it.until > now }?.let { return it.incidents }
            cells.values.removeAll { it.until <= now }
        }
        val answer = service.incidents(col * CELL, row * CELL, (col + 1) * CELL, (row + 1) * CELL)
        // A failed or refused cell is not asked again at once: offline, every pan would fail the same.
        val fetched = (answer as? Answer.Ok)?.value
        synchronized(cells) { cells[key] = Cell(now + if (fetched != null) HOLD_MILLIS else RETRY_MILLIS, fetched.orEmpty()) }
        return fetched.orEmpty()
    }

    override fun onPinTap(pinId: String) {
        open = known[pinId] ?: return
        host.openFeature(INCIDENT_FEATURE)
    }

    companion object {
        /** Beyond this the view is a country, not a road: the pins would be a flood and the calls many. */
        const val MAX_RADIUS = 25_000
        // Half a degree is about 55 by 38 km, far under TomTom's 10 000 km² limit and few calls per view.
        const val CELL = 0.5
        const val HOLD_MILLIS = 3 * 60_000L
        const val RETRY_MILLIS = 30_000L
        private const val METERS_PER_DEGREE = 111_320.0
    }
}

internal fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dy = (lat2 - lat1) * 111_320.0
    val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
    return sqrt(dx * dx + dy * dy)
}

/** TomTom's places as a source of place icons. */
internal class TomTomPlaces(private val service: TomTomService) : ModulePlaceProvider {

    override val title = "TomTom"
    override val attribution = "Place data © TomTom"

    private class Held(val until: Long, val places: List<ModulePoi>)

    private val nearbyCache = LinkedHashMap<String, Held>()
    private val detailCache = LinkedHashMap<String, Pair<Long, Map<String, String>?>>()

    override fun categories(): Array<String> = Categories.supported()

    override fun nearby(latitude: Double, longitude: Double, radiusMeters: Int, categories: Array<String>, limit: Int): ModulePoiAnswer {
        if (!service.on(Feature.PLACES)) return ModulePoiAnswer(false, false, emptyArray())
        val wanted = categories.filter { it in Categories.supported() }
        val types = Categories.typesFor(wanted)
        if (types.isEmpty()) return ModulePoiAnswer(true, false, emptyArray())
        val now = System.currentTimeMillis()
        // Two kilometres of the map's centre make the same question: a pan across a town asks once.
        val key = "${Math.round(latitude / 0.02)}/${Math.round(longitude / 0.02)}/$radiusMeters/${wanted.sorted().joinToString(",")}"
        val cached = synchronized(nearbyCache) { nearbyCache[key]?.takeIf { it.until > now } }
        val found = cached?.places ?: when (val a = service.nearby(latitude, longitude, radiusMeters, types, 100)) {
            is Answer.Exhausted -> return ModulePoiAnswer(false, true, emptyArray())
            is Answer.Failed -> return ModulePoiAnswer(false, false, emptyArray())
            is Answer.Ok -> a.value.mapNotNull { h ->
                val lat = h.lat ?: return@mapNotNull null
                val lon = h.lon ?: return@mapNotNull null
                val category = Categories.categoryOf(h.poiTypes, wanted) ?: return@mapNotNull null
                ModulePoi(h.id, h.title, lat, lon, category, h.tags)
            }.also { list ->
                synchronized(nearbyCache) {
                    nearbyCache.values.removeAll { it.until <= now }
                    while (nearbyCache.size >= 32) nearbyCache.remove(nearbyCache.keys.first())
                    nearbyCache[key] = Held(now + 10 * 60_000L, list)
                }
            }
        }
        val near = found.map { it to meters(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= radiusMeters }.sortedBy { it.second }.take(limit).map { it.first }
        return ModulePoiAnswer(true, false, near.toTypedArray())
    }

    override fun details(placeId: String): Map<String, String>? {
        if (!service.on(Feature.PLACES)) return null
        val now = System.currentTimeMillis()
        synchronized(detailCache) { detailCache[placeId]?.takeIf { it.first > now }?.let { return it.second } }
        val tags = (service.details(placeId) as? Answer.Ok)?.value?.takeIf { it.isNotEmpty() } ?: return null
        synchronized(detailCache) {
            detailCache.values.removeAll { it.first <= now }
            while (detailCache.size >= 64) detailCache.remove(detailCache.keys.first())
            detailCache[placeId] = now + 10 * 60_000L to tags
        }
        return tags
    }
}

/** TomTom's search, put before the app's own. */
internal class TomTomSearch(private val service: TomTomService) : ModuleGeocoder {

    override val attribution = "Results from TomTom"

    override fun suggest(query: String, nearLatitude: Double, nearLongitude: Double, limit: Int): ModuleGeocodeResult =
        result(service.on(Feature.SEARCH)) { service.suggest(query, nearLatitude, nearLongitude, limit) }

    override fun search(query: String, nearLatitude: Double, nearLongitude: Double, limit: Int): ModuleGeocodeResult =
        result(service.on(Feature.SEARCH)) { service.search(query, nearLatitude, nearLongitude, limit) }

    override fun reverse(latitude: Double, longitude: Double): String? {
        if (!service.on(Feature.SEARCH)) return null
        return (service.reverse(latitude, longitude) as? Answer.Ok)?.value
    }

    private fun result(on: Boolean, ask: () -> Answer<List<Hit>>): ModuleGeocodeResult {
        val none = ModuleGeocodeResult(false, false, emptyArray(), emptyArray(), DoubleArray(0), DoubleArray(0))
        if (!on) return none
        return when (val a = ask()) {
            is Answer.Exhausted -> ModuleGeocodeResult(false, true, emptyArray(), emptyArray(), DoubleArray(0), DoubleArray(0))
            is Answer.Failed -> none
            is Answer.Ok -> {
                val hits = a.value.filter { it.lat != null && it.lon != null }
                ModuleGeocodeResult(
                    true, false,
                    hits.map { it.title }.toTypedArray(),
                    hits.map { it.subtitle }.toTypedArray(),
                    hits.map { it.lat!! }.toDoubleArray(),
                    hits.map { it.lon!! }.toDoubleArray()
                )
            }
        }
    }
}

/**
 * Drive times from one point to a few, one Calculate Route call each. Matrix Routing would be one
 * request, but it has no motorcycle and its free allowance (2 500 cells) is an eighth of Routing's.
 */
internal class TomTomDrive(private val service: TomTomService) : ModuleTravelTimes {

    override val attribution = "Drive times by TomTom"

    override fun secondsTo(fromLatitude: Double, fromLongitude: Double, toLatitudes: DoubleArray, toLongitudes: DoubleArray): DoubleArray? {
        if (!service.on(Feature.DRIVE)) return null
        val n = minOf(toLatitudes.size, toLongitudes.size, MAX_DESTINATIONS)
        val out = DoubleArray(toLatitudes.size) { Double.NaN }
        if (n == 0) return out
        val room = service.usage.room(Api.ROUTING, n)
        if (room == 0) return null
        val pool = Executors.newFixedThreadPool(4)
        try {
            val calls: List<Future<Answer<Double?>>> = (0 until room).map { i ->
                pool.submit<Answer<Double?>> { service.drive(fromLatitude, fromLongitude, toLatitudes[i], toLongitudes[i]) }
            }
            var answered = 0
            calls.forEachIndexed { i, f ->
                val a = runCatching { f.get() }.getOrNull()
                if (a is Answer.Ok) {
                    answered++
                    out[i] = a.value ?: Double.NaN
                }
            }
            return if (answered == 0) null else out
        } finally {
            pool.shutdown()
        }
    }

    companion object {
        const val MAX_DESTINATIONS = 25
    }
}
