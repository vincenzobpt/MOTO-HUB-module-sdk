// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Builders for the catalogue tests: valid experience JSON, packs, indexes, checksums. */
object CatalogTestPacks {

    /** A valid experience as JSON; [edit] changes it to break one rule at a time. */
    fun experienceJson(id: String, edit: JSONObject.() -> Unit = {}): JSONObject = JSONObject()
        .put("id", id)
        .put("name", "Ride $id")
        .put("description", "A test ride.")
        .put("region", "Alps")
        .put("from", place("Start", 46.5, 11.3))
        .put("to", place("End", 46.9, 12.0))
        .put("via", JSONArray().put(place("Pass", 46.7, 11.6)))
        .put("km", 120)
        .put("minutes", 180)
        .put("nature", 80)
        .put("twisty", 70)
        .put("gravel", 0)
        .put("maxElevationM", 2100)
        .put("shape", JSONArray().put(JSONArray().put(46.5).put(11.3)).put(JSONArray().put(46.7).put(11.6)).put(JSONArray().put(46.9).put(12.0)))
        .apply(edit)

    fun place(name: String, lat: Double, lon: Double): JSONObject =
        JSONObject().put("name", name).put("lat", lat).put("lon", lon)

    fun packJson(
        country: String = "IT",
        version: Int = 1,
        experiences: List<JSONObject>,
        name: String = "Italy",
    ): String = JSONObject()
        .put("schema", 1).put("country", country).put("name", name).put("version", version)
        .put("experiences", JSONArray().apply { experiences.forEach { put(it) } })
        .toString()

    /** [count] valid experiences with ids `<cc>-ride-1`..; the ones in [country] only. */
    fun goodPack(country: String = "IT", version: Int = 1, count: Int = 12, name: String = "Italy"): String =
        packJson(country, version, (1..count).map { experienceJson("${country.lowercase()}-ride-$it") }, name)

    fun indexJson(vararg entries: IndexLine): String = JSONObject()
        .put("schema", 1)
        .put("packs", JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("country", it.country).put("name", it.name).put("version", it.version)
                    .put("sha256", it.sha256).put("bytes", it.bytes))
            }
        }).toString()

    data class IndexLine(val country: String, val name: String, val version: Int, val sha256: String, val bytes: Long)

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The index line that fits [packText] exactly. */
    fun lineFor(country: String, name: String, version: Int, packText: String) =
        IndexLine(country, name, version, sha256(packText), packText.toByteArray(Charsets.UTF_8).size.toLong())
}
