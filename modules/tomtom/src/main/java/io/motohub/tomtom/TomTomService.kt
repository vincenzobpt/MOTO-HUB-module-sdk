// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import io.motohub.android.module.MotoHubModuleHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Semaphore

/** What a call to TomTom came to: an answer, a month's allowance used up, or a reason. */
sealed interface Answer<out T> {
    class Ok<T>(val value: T) : Answer<T>
    object Exhausted : Answer<Nothing>
    class Failed(val why: String) : Answer<Nothing>
}

/** The five things the module does; each can be switched off on its page. */
enum class Feature(val key: String) {
    TRAFFIC("traffic"), PLACES("places"), SEARCH("search"), DRIVE("drive"), ROAD("road")
}

/** One line of "Check my key": which part, and whether TomTom let it through (its own words if not). */
class CheckRow(val name: String, val problem: String?)

/**
 * The rider's TomTom: the key, the terms they accepted, which features are on, and every call.
 *
 * Endpoints (all https; TomTom's docs, October 2026):
 * - Places Search v3: `POST /maps/orbis/places/suggest`, `POST /maps/orbis/places/discover`,
 *   `GET /maps/orbis/places/details/pois/{id}`; key in the `TomTom-Api-Key` header.
 * - Reverse Geocoding v2: `GET /maps/orbis/places/reverseGeocode`.
 * - Traffic: `GET /traffic/services/5/incidentDetails` and the flow tiles of `/traffic/map/4/tile/flow`.
 * - Routing: `GET /routing/1/calculateRoute` (motorcycle, live traffic, summary only).
 * - Snap to Roads: `GET /snapToRoads/1`.
 *
 * Nothing TomTom sends is written down: the module keeps its answers in memory only, for minutes,
 * and writes just the key (sealed), the switches and the monthly call counts.
 *
 * Blocking: call it off the main thread.
 */
class TomTomService(private val host: MotoHubModuleHost) {

    private val configFile = File(host.storage, "config.properties")
    val usage = Usage(File(host.storage, "usage.properties"))
    // The free key has a queries-per-second limit; a handful at a time is well under it.
    private val gate = Semaphore(4)

    @Volatile var apiKey = ""
    @Volatile var termsAccepted = false
    private val switches = java.util.concurrent.ConcurrentHashMap<Feature, Boolean>()
    @Volatile var lastCheck: List<CheckRow>? = null

    init {
        load()
    }

    /** A key and the terms accepted: the only state in which the module calls TomTom. */
    val ready: Boolean get() = apiKey.isNotBlank() && termsAccepted

    fun isOn(feature: Feature): Boolean = switches[feature] ?: true

    fun setOn(feature: Feature, on: Boolean) {
        switches[feature] = on
        save()
    }

    /** Set up and switched on. */
    fun on(feature: Feature): Boolean = ready && isOn(feature)

    /** "•••• 3F2A": enough to know which key it is. */
    val maskedKey: String get() = if (apiKey.isBlank()) "" else "•••• " + apiKey.trim().takeLast(4).uppercase()

    fun save() {
        val p = Properties()
        p.setProperty("keyEnc", if (apiKey.isBlank()) "" else Secret.seal(apiKey.trim()))
        p.setProperty("terms", termsAccepted.toString())
        Feature.entries.forEach { p.setProperty("on." + it.key, isOn(it).toString()) }
        host.storage.mkdirs()
        configFile.outputStream().use { p.store(it, "TomTom module") }
    }

    private fun load() {
        if (!configFile.isFile) return
        val p = Properties()
        runCatching { configFile.inputStream().use { p.load(it) } }
        apiKey = p.getProperty("keyEnc", "").takeIf { it.isNotEmpty() }?.let { Secret.open(it) }.orEmpty()
        termsAccepted = p.getProperty("terms") == "true"
        Feature.entries.forEach { f -> p.getProperty("on." + f.key)?.let { switches[f] = it == "true" } }
    }

    fun forgetKey() {
        apiKey = ""
        lastCheck = null
        save()
    }

    // --- Places and search ---------------------------------------------------------------

    private val hitAttributes = "results(id,type,title,subtitles,position,poiTypes,poiBrands,distanceInMeters)"

    /** Suggestions for a half-typed query. Suggest does not document a position, so a result without one is looked up with Discover. */
    fun suggest(query: String, lat: Double, lon: Double, limit: Int): Answer<List<Hit>> {
        val body = JSONObject().put("query", query).put("maxResults", limit.coerceIn(1, 10)).also { bias(it, lat, lon) }
        val first = orbis(Api.SUGGEST, "POST", "/maps/orbis/places/suggest", "3", hitAttributes, body.toString()) { Parse.hits(it) }
        if (first is Answer.Ok && first.value.isNotEmpty() && first.value.any { it.lat == null }) return search(query, lat, lon, limit)
        return first
    }

    /** Results for a finished query: places, addresses, streets and areas. */
    fun search(query: String, lat: Double, lon: Double, limit: Int): Answer<List<Hit>> {
        val body = JSONObject().put("query", query).put("maxResults", limit.coerceIn(1, 100))
            .put("filters", JSONObject().put("types", JSONArray(listOf("poi", "address", "street", "intersection", "area"))))
            .also { bias(it, lat, lon) }
        return orbis(Api.DISCOVER, "POST", "/maps/orbis/places/discover", "3", hitAttributes, body.toString()) { Parse.hits(it) }
    }

    /** Places of [typeIds] within [radiusMeters], with the address and brand but not the hours (those come with [details]). */
    fun nearby(lat: Double, lon: Double, radiusMeters: Int, typeIds: List<String>, limit: Int): Answer<List<Hit>> {
        val center = JSONArray(listOf(lon, lat))
        val body = JSONObject().put("query", "").put("maxResults", limit.coerceIn(1, 100))
            .put("origin", point(lat, lon))
            .put(
                "filters",
                JSONObject().put("types", JSONArray(listOf("poi"))).put("poiTypes", JSONArray(typeIds))
                    .put("geometry", JSONObject().put("type", "circle").put("center", center).put("radiusInMeters", radiusMeters.coerceIn(1, 1_000_000)))
            )
            .put("preferences", JSONObject().put("geometry", point(lat, lon)))
        return orbis(
            Api.DISCOVER, "POST", "/maps/orbis/places/discover", "3",
            "results(id,type,title,position,poiTypes,poiBrands,address,distanceInMeters)", body.toString()
        ) { Parse.hits(it) }
    }

    fun details(placeId: String): Answer<Map<String, String>> =
        orbis(
            Api.DETAILS, "GET", "/maps/orbis/places/details/pois/" + enc(placeId), "3",
            "id,type,title,address,poiBrands,contacts,openingHours", null
        ) { Parse.details(it) }

    /** The title TomTom gives to the place at a point, or null (nothing within a few hundred metres). */
    fun reverse(lat: Double, lon: Double): Answer<String?> =
        orbis(
            Api.REVERSE, "GET", "/maps/orbis/places/reverseGeocode?position=" + fmt(lon) + "," + fmt(lat) + "&radiusInMeters=300",
            "2", "results(title)", null
        ) { Parse.reverse(it) }

    private fun point(lat: Double, lon: Double) = JSONObject().put("type", "point").put("coordinates", JSONArray(listOf(lon, lat)))

    private fun bias(body: JSONObject, lat: Double, lon: Double) {
        if (lat.isNaN() || lon.isNaN()) return
        body.put("origin", point(lat, lon)).put("preferences", JSONObject().put("geometry", point(lat, lon)))
    }

    // --- Traffic, road info, drive times ---------------------------------------------------

    private val incidentFields =
        "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,startTime,endTime,from,to,length,delay,roadNumbers}}}"

    fun incidents(west: Double, south: Double, east: Double, north: Double): Answer<List<Incident>> =
        classic(
            Api.INCIDENTS,
            "/traffic/services/5/incidentDetails?bbox=" + fmt(west) + "," + fmt(south) + "," + fmt(east) + "," + fmt(north) +
                "&fields=" + enc(incidentFields) + "&timeValidityFilter=present"
        ) { Parse.incidents(it) }

    /** The road at a point of the map: its name, number and posted limit, from reverse geocoding (40 m around). */
    fun roadAt(lat: Double, lon: Double): Answer<Road?> {
        val path = "/search/2/reverseGeocode/" + fmt(lat) + "," + fmt(lon) + ".json?returnSpeedLimit=true&radius=40"
        // The bare language ("en", "pt"): TomTom refuses many full tags, a phone in en-PT among them.
        val first = classic(Api.REVERSE, path + "&language=" + enc(Locale.getDefault().language)) { Parse.road(it) }
        // A language it still does not know: once more in TomTom's own default.
        return if (first is Answer.Failed && first.why.contains("language", ignoreCase = true)) {
            classic(Api.REVERSE, path) { Parse.road(it) }
        } else first
    }

    /** Seconds by motorcycle with live traffic; one call per destination, summary only. */
    fun drive(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Answer<Double?> =
        classic(
            Api.ROUTING,
            "/routing/1/calculateRoute/" + fmt(fromLat) + "," + fmt(fromLon) + ":" + fmt(toLat) + "," + fmt(toLon) +
                "/json?travelMode=motorcycle&traffic=true&routeType=fastest&routeRepresentation=summaryOnly"
        ) { Parse.travelSeconds(it) }

    @Volatile private var notice: String? = null

    /**
     * TomTom's own copyright notice, from its Copyright API as its terms ask, once per run of the
     * app: one small text, not counted against an allowance the page shows. Null until it answers.
     */
    fun copyrights(): String? {
        notice?.let { return it }
        val answer = classic(null, "/map/2/copyrights") { it.trim().takeIf { text -> text.isNotEmpty() } }
        return (answer as? Answer.Ok)?.value?.also { notice = it }
    }

    /** The flow tiles' URL for the map; the key is in it, so it goes to the app and nowhere else. */
    fun flowTiles(dark: Boolean): String =
        "https://api.tomtom.com/traffic/map/4/tile/flow/" + (if (dark) "relative0-dark" else "relative0") +
            "/{z}/{x}/{y}.png?tileSize=256&key=" + enc(apiKey.trim())

    // --- Check ------------------------------------------------------------------------------

    /** One cheap call per family; each costs one call of its allowance. */
    fun check(): List<CheckRow> {
        fun row(name: String, a: Answer<*>) = CheckRow(
            name,
            when (a) {
                is Answer.Ok -> null
                is Answer.Exhausted -> "This month's free allowance is used up."
                is Answer.Failed -> a.why
            }
        )
        val rows = listOf(
            row(
                "Places",
                orbis(Api.DISCOVER, "POST", "/maps/orbis/places/discover", "3", "results(id)", """{"query":"cafe","maxResults":1}""") { it }
            ),
            row("Traffic", incidents(4.8854, 52.3693, 4.8979, 52.3750)),
            row("Search", orbis(Api.SUGGEST, "POST", "/maps/orbis/places/suggest", "3", "results(id)", """{"query":"ams","maxResults":1}""") { it }),
            row("Road info", roadAt(52.37576, 4.61049)),
            row("Drive times", drive(52.37576, 4.61049, 52.38341, 4.61409))
        )
        lastCheck = rows
        return rows
    }

    // --- HTTP --------------------------------------------------------------------------------

    private fun <T> orbis(
        api: Api, method: String, path: String, version: String, attributes: String, body: String?, read: (String) -> T
    ): Answer<T> = send(api, method, "https://api.tomtom.com$path", read) { c ->
        c.setRequestProperty("TomTom-Api-Key", apiKey.trim())
        c.setRequestProperty("TomTom-Api-Version", version)
        c.setRequestProperty("Attributes", attributes)
        body?.let { c.setRequestProperty("Content-Type", "application/json") }
        body
    }

    private fun <T> classic(api: Api?, pathAndQuery: String, read: (String) -> T): Answer<T> =
        send(api, "GET", "https://api.tomtom.com$pathAndQuery" + (if ('?' in pathAndQuery) "&" else "?") + "key=" + enc(apiKey.trim()), read) { null }

    private fun <T> send(api: Api?, method: String, url: String, read: (String) -> T, prepare: (HttpURLConnection) -> String?): Answer<T> {
        if (!ready) return Answer.Failed("TomTom is not set up.")
        if (apiKey.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E }) return Answer.Failed("The key has characters a key cannot have.")
        if (api != null && !usage.allowed(api)) return Answer.Exhausted
        gate.acquire()
        try {
            api?.let(usage::add)
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Language", languages())
            try {
                val body = prepare(c)
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                if (code !in 200..299) {
                    val said = runCatching { c.errorStream.bufferedReader().readText() }.getOrNull()?.let { Parse.errorWords(it) }
                    return Answer.Failed(listOfNotNull(problem(code), said).joinToString(" "))
                }
                return Answer.Ok(read(c.inputStream.bufferedReader().readText()))
            } finally {
                c.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            return Answer.Failed("TomTom did not answer in time.")
        } catch (e: IOException) {
            return Answer.Failed("TomTom is not reachable.")
        } catch (e: Exception) {
            return Answer.Failed("TomTom sent something this module cannot read.")
        } finally {
            gate.release()
        }
    }

    private fun problem(status: Int) = when (status) {
        401, 403 -> "TomTom refused the key. Is this API switched on for it in your TomTom dashboard?"
        429 -> "TomTom says: too many requests. Wait a moment."
        else -> "TomTom answered $status."
    }

    private fun languages(): String {
        val tag = Locale.getDefault().toLanguageTag()
        return if (tag.startsWith("en")) "$tag,en;q=0.8" else "$tag,en;q=0.5"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val USER_AGENT = "MOTO-HUB-module-tomtom/0.1.3"

        /** Six decimals: about a tenth of a metre, and no locale comma. */
        fun fmt(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
    }
}
