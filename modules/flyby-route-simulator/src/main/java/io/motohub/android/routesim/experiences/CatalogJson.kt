// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Reads, checks and writes the catalogue's JSON (a country pack, the index). org.json is the
// platform's. Bad content is dropped and described, never thrown: a pack file comes from the
// network or from a rider's disk and must not be able to take the page down.
package io.motohub.android.routesim.experiences

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** A pack read from text: the valid experiences, and a line for every thing that was dropped. */
data class ParsedPack(val pack: CountryPack, val problems: List<String>)

/** One country's line in `index.json`. [sha256] is of the exact bytes of `<country>.json`. */
data class IndexEntry(val country: String, val name: String, val version: Int, val sha256: String, val bytes: Long)

data class CatalogIndex(val packs: List<IndexEntry>)

object CatalogJson {

    const val SCHEMA = 1

    /** A ride whose ends are closer than this is a loop, and must say through which places. */
    const val LOOP_METRES = 1000.0

    private val ID_PATTERN = Regex("[a-z]{2}-[a-z0-9]+(-[a-z0-9]+)*")
    private val SHA_PATTERN = Regex("[0-9a-f]{64}")

    /**
     * Never throws. Text that is not a readable pack gives an empty pack (country "", version 0) and
     * one problem; an experience that breaks a rule is left out and gets one problem of its own.
     */
    fun parsePack(text: String): ParsedPack {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return unreadable("not JSON")
        }
        val schema = root.optInt("schema", -1)
        if (schema != SCHEMA) return unreadable("unsupported schema $schema")
        val country = (root.opt("country") as? String)?.trim().orEmpty()
        if (country.length != 2 || country.any { it !in 'A'..'Z' }) return unreadable("bad country \"$country\"")
        val version = intOf(root.opt("version"))
        if (version == null || version < 1) return unreadable("bad version")
        val name = (root.opt("name") as? String)?.trim().orEmpty().ifEmpty { country }
        val array = root.optJSONArray("experiences") ?: return unreadable("no experiences list")

        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        val kept = ArrayList<Experience>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            val label = (obj?.opt("id") as? String) ?: "#$i"
            if (obj == null) {
                problems += "$label: not an object"
                continue
            }
            val parsed = try {
                experienceFrom(obj, country, problems, label)
            } catch (e: Exception) {
                problems += "$label: ${e.message ?: "unreadable"}"
                null
            }
            val experience = parsed ?: continue
            if (!seen.add(experience.id)) {
                problems += "${experience.id}: duplicate id"
                continue
            }
            kept += experience
        }
        return ParsedPack(CountryPack(country, name, version, kept), problems)
    }

    /** The pack as `<cc>.json` text. Parsing the result gives the same pack back. */
    fun packToJson(pack: CountryPack): String {
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("country", pack.country)
            .put("name", pack.name)
            .put("version", pack.version)
            .put("experiences", JSONArray().apply { pack.experiences.forEach { put(experienceToJson(it)) } })
        return root.toString(2)
    }

    /** Never throws; unreadable text and unreadable lines give fewer entries, not an error. */
    fun parseIndex(text: String): CatalogIndex {
        val array = try {
            val root = JSONObject(text)
            if (root.optInt("schema", -1) != SCHEMA) return CatalogIndex(emptyList())
            root.optJSONArray("packs") ?: return CatalogIndex(emptyList())
        } catch (e: Exception) {
            return CatalogIndex(emptyList())
        }
        val seen = HashSet<String>()
        val entries = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val country = (o.opt("country") as? String)?.trim().orEmpty()
            val version = intOf(o.opt("version"))
            val sha = (o.opt("sha256") as? String)?.trim()?.lowercase().orEmpty()
            val bytes = (o.opt("bytes") as? Number)?.toLong() ?: 0L
            if (country.length != 2 || country.any { it !in 'A'..'Z' }) return@mapNotNull null
            if (version == null || version < 1 || !SHA_PATTERN.matches(sha) || bytes < 0) return@mapNotNull null
            if (!seen.add(country)) return@mapNotNull null
            IndexEntry(country, (o.opt("name") as? String)?.trim().orEmpty().ifEmpty { country }, version, sha, bytes)
        }
        return CatalogIndex(entries)
    }

    private fun unreadable(why: String) = ParsedPack(CountryPack("", "", 0, emptyList()), listOf("pack: $why"))

    /** An integer JSON number, or null; 85.0 counts, 85.5 does not. */
    private fun intOf(value: Any?): Int? {
        val n = value as? Number ?: return null
        val d = n.toDouble()
        if (d.isNaN() || d != Math.rint(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) return null
        return d.toInt()
    }

    private fun doubleOf(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun placeFrom(value: Any?): ExperiencePlace? {
        val o = value as? JSONObject ?: return null
        val lat = doubleOf(o.opt("lat")) ?: return null
        val lon = doubleOf(o.opt("lon")) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val name = (o.opt("name") as? String)?.trim().orEmpty()
        if (name.isEmpty()) return null
        return ExperiencePlace(name, lat, lon)
    }

    /** The valid experience, or null after adding the reason to [problems]. */
    private fun experienceFrom(o: JSONObject, country: String, problems: MutableList<String>, label: String): Experience? {
        fun reject(why: String): Experience? {
            problems += "$label: $why"
            return null
        }

        val id = o.opt("id") as? String ?: return reject("no id")
        if (!ID_PATTERN.matches(id) || !id.startsWith(country.lowercase() + "-")) {
            return reject("id is not <${country.lowercase()}>-<kebab>")
        }
        val name = (o.opt("name") as? String)?.trim().orEmpty()
        if (name.isEmpty()) return reject("no name")
        val from = placeFrom(o.opt("from")) ?: return reject("bad \"from\"")
        val to = placeFrom(o.opt("to")) ?: return reject("bad \"to\"")

        val viaArray = o.opt("via")
        val via = ArrayList<ExperiencePlace>()
        if (viaArray != null && viaArray != JSONObject.NULL) {
            if (viaArray !is JSONArray) return reject("\"via\" is not a list")
            if (viaArray.length() > MAX_VIA) return reject("more than $MAX_VIA via places")
            for (i in 0 until viaArray.length()) via += placeFrom(viaArray.opt(i)) ?: return reject("bad via #$i")
        }

        val km = doubleOf(o.opt("km")) ?: return reject("no km")
        if (km < MIN_KM || km > MAX_KM) return reject("km $km outside $MIN_KM..$MAX_KM")
        val minutes = doubleOf(o.opt("minutes")) ?: return reject("no minutes")
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) return reject("minutes $minutes outside $MIN_MINUTES..$MAX_MINUTES")
        val nature = percent(o.opt("nature")) ?: return reject("nature is not 0..100")
        val twisty = percent(o.opt("twisty")) ?: return reject("twisty is not 0..100")
        val gravel = percent(o.opt("gravel")) ?: return reject("gravel is not 0..100")
        val elevation = intOf(o.opt("maxElevationM")) ?: return reject("no maxElevationM")
        if (elevation < MIN_ELEVATION_M || elevation > MAX_ELEVATION_M) return reject("maxElevationM $elevation is not plausible")

        val shapeArray = o.opt("shape") as? JSONArray ?: return reject("no shape")
        if (shapeArray.length() < MIN_SHAPE_POINTS) return reject("shape has fewer than $MIN_SHAPE_POINTS points")
        val shape = ArrayList<Pair<Double, Double>>(shapeArray.length())
        for (i in 0 until shapeArray.length()) {
            val p = shapeArray.opt(i) as? JSONArray ?: return reject("shape point #$i is not a pair")
            val lat = doubleOf(p.opt(0)) ?: return reject("shape point #$i has no latitude")
            val lon = doubleOf(p.opt(1)) ?: return reject("shape point #$i has no longitude")
            if (p.length() != 2 || lat !in -90.0..90.0 || lon !in -180.0..180.0) return reject("shape point #$i is invalid")
            shape += lat to lon
        }

        if (distanceMetres(from, to) <= LOOP_METRES && via.isEmpty()) {
            return reject("\"from\" and \"to\" are the same place and no via says how it loops")
        }

        return Experience(
            id = id,
            name = name,
            description = (o.opt("description") as? String)?.trim().orEmpty(),
            region = (o.opt("region") as? String)?.trim().orEmpty(),
            from = from,
            to = to,
            via = via,
            km = km,
            minutes = minutes,
            nature = nature,
            twisty = twisty,
            gravel = gravel,
            maxElevationM = elevation,
            shape = shape,
        )
    }

    private fun percent(value: Any?): Int? = intOf(value)?.takeIf { it in 0..100 }

    private fun experienceToJson(e: Experience): JSONObject = JSONObject()
        .put("id", e.id)
        .put("name", e.name)
        .put("description", e.description)
        .put("region", e.region)
        .put("from", placeToJson(e.from))
        .put("to", placeToJson(e.to))
        .put("via", JSONArray().apply { e.via.forEach { put(placeToJson(it)) } })
        .put("km", e.km)
        .put("minutes", e.minutes)
        .put("nature", e.nature)
        .put("twisty", e.twisty)
        .put("gravel", e.gravel)
        .put("maxElevationM", e.maxElevationM)
        .put("shape", JSONArray().apply { e.shape.forEach { put(JSONArray().put(it.first).put(it.second)) } })

    private fun placeToJson(p: ExperiencePlace): JSONObject =
        JSONObject().put("name", p.name).put("lat", p.latitude).put("lon", p.longitude)

    /** Haversine; good to a few metres, which is all a "same place?" test needs. */
    private fun distanceMetres(a: ExperiencePlace, b: ExperiencePlace): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.latitude)
        val p2 = Math.toRadians(b.latitude)
        val dp = p2 - p1
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1.0, sqrt(max(0.0, h))))
    }

    const val MIN_KM = 5.0
    const val MAX_KM = 500.0
    const val MIN_MINUTES = 10.0
    const val MAX_MINUTES = 900.0
    const val MAX_VIA = 4
    const val MIN_SHAPE_POINTS = 2
    private const val MIN_ELEVATION_M = -500
    private const val MAX_ELEVATION_M = 9000
}
