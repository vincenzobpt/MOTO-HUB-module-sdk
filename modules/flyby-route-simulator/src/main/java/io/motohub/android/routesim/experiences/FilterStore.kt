// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The rider's filters, remembered per country, and the country last chosen: one JSON file in the
// module's storage directory. org.json is the platform's. A file that cannot be read is treated as
// empty, and nothing here throws.
package io.motohub.android.routesim.experiences

import java.io.File
import org.json.JSONObject

class FilterStore(private val storageDir: File) {

    private val file: File get() = File(storageDir, FILE_NAME)
    private val lock = Any()

    /** The saved filters of [country], or the defaults when there are none or they are damaged. */
    fun load(country: String): ExperienceFilters = synchronized(lock) {
        try {
            read().optJSONObject(KEY_COUNTRIES)?.optJSONObject(key(country))?.let { fromJson(it) }
        } catch (e: Exception) {
            null
        } ?: ExperienceFilters.DEFAULT
    }

    fun save(country: String, filters: ExperienceFilters) = synchronized(lock) {
        try {
            val root = read()
            val countries = root.optJSONObject(KEY_COUNTRIES) ?: JSONObject().also { root.put(KEY_COUNTRIES, it) }
            countries.put(key(country), toJson(sanitise(filters)))
            write(root)
        } catch (e: Exception) {
            // The filters are a convenience: losing them is better than failing the page.
        }
    }

    /** Back to the defaults for [country]. */
    fun reset(country: String) = synchronized(lock) {
        try {
            val root = read()
            val countries = root.optJSONObject(KEY_COUNTRIES) ?: return@synchronized
            if (countries.remove(key(country)) != null) write(root)
        } catch (e: Exception) {
            // Nothing to undo.
        }
    }

    /** The country the rider chose last, or null if none. */
    fun lastCountry(): String? = synchronized(lock) {
        try {
            read().optString(KEY_LAST_COUNTRY, "").trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    fun saveLastCountry(country: String) = synchronized(lock) {
        try {
            val root = read()
            root.put(KEY_LAST_COUNTRY, country.trim())
            write(root)
        } catch (e: Exception) {
            // Same as save.
        }
    }

    private fun key(country: String): String = country.trim().uppercase()

    /** The file's root, or a fresh one when the file is missing or damaged. */
    private fun read(): JSONObject {
        val f = file
        if (!f.isFile) return JSONObject()
        return try {
            JSONObject(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun write(root: JSONObject) {
        storageDir.mkdirs()
        root.put(KEY_VERSION, 1)
        val tmp = File(storageDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "filters could not be written" }
        }
    }

    private fun toJson(f: ExperienceFilters): JSONObject = JSONObject()
        .put("minKm", f.minKm).put("maxKm", f.maxKm)
        .put("minMinutes", f.minMinutes).put("maxMinutes", f.maxMinutes)
        .put("detours", f.detours)
        .apply {
            f.nature?.let { put("nature", it) }
            f.twisty?.let { put("twisty", it) }
            f.gravel?.let { put("gravel", it) }
            f.culture?.let { put("culture", it) }
            f.region?.let { put("region", it) }
        }

    /** Missing or malformed fields fall back on the defaults, so an older or damaged file still opens. */
    private fun fromJson(o: JSONObject): ExperienceFilters {
        val d = ExperienceFilters.DEFAULT
        return sanitise(
            ExperienceFilters(
                minKm = o.optInt("minKm", d.minKm),
                maxKm = o.optInt("maxKm", d.maxKm),
                minMinutes = o.optInt("minMinutes", d.minMinutes),
                maxMinutes = o.optInt("maxMinutes", d.maxMinutes),
                nature = slider(o, "nature"),
                twisty = slider(o, "twisty"),
                gravel = slider(o, "gravel"),
                culture = slider(o, "culture"),
                region = o.takeUnless { it.isNull("region") }?.optString("region", "")?.trim()?.takeIf { it.isNotEmpty() },
                detours = o.optInt("detours", d.detours),
            ),
        )
    }

    private fun slider(o: JSONObject, name: String): Int? =
        if (o.isNull(name)) null else if (o.opt(name) is Number) o.optInt(name) else null

    /** In range and in order; a swapped minimum and maximum are exchanged rather than dropped. */
    private fun sanitise(f: ExperienceFilters): ExperienceFilters {
        val km1 = f.minKm.coerceIn(0, ExperienceFilters.DEFAULT_MAX_KM)
        val km2 = f.maxKm.coerceIn(0, ExperienceFilters.DEFAULT_MAX_KM)
        val m1 = f.minMinutes.coerceIn(0, ExperienceFilters.DEFAULT_MAX_MINUTES)
        val m2 = f.maxMinutes.coerceIn(0, ExperienceFilters.DEFAULT_MAX_MINUTES)
        return ExperienceFilters(
            minKm = minOf(km1, km2), maxKm = maxOf(km1, km2),
            minMinutes = minOf(m1, m2), maxMinutes = maxOf(m1, m2),
            nature = f.nature?.coerceIn(0, 100),
            twisty = f.twisty?.coerceIn(0, 100),
            gravel = f.gravel?.coerceIn(0, 100),
            culture = f.culture?.coerceIn(0, 100),
            region = f.region?.trim()?.takeIf { it.isNotEmpty() },
            detours = f.detours.coerceIn(0, ExperienceFilters.MAX_DETOURS),
        )
    }

    private companion object {
        const val FILE_NAME = "experience-filters.json"
        const val KEY_VERSION = "version"
        const val KEY_COUNTRIES = "countries"
        const val KEY_LAST_COUNTRY = "lastCountry"
    }
}
