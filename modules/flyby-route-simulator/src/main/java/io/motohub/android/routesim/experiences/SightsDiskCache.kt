// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The sights found along an experience's road, kept on disk so that a new app start does not ask the
// public map servers again for what they answered a few days ago. Blocking file work: off the main thread.
package io.motohub.android.routesim.experiences

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `<storageDir>/experience-sights/<key>.json`, one file per experience (its id and a hash of its
 * places) and search radius, valid for [TTL_MILLIS]. Only found, complete, non-empty searches are
 * written. A file that cannot be read, is damaged, is for something else or is too old is ignored (and
 * simply searched again); nothing here throws.
 */
internal class SightsDiskCache(
    storageDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val dir = File(storageDir, DIR_NAME)
    private var pruned = false

    /** The file that holds [id]'s sights for [signature] and [radiusM]. */
    internal fun fileFor(id: String, signature: Int, radiusM: Int): File {
        val safe = id.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("").take(MAX_NAME)
        return File(dir, "$safe-${Integer.toHexString(signature)}-$radiusM.json")
    }

    /** The sights kept for this experience and radius, or null when there are none worth using. */
    fun read(id: String, signature: Int, radiusM: Int): List<Sight>? {
        return try {
            val file = fileFor(id, signature, radiusM)
            if (!file.isFile || file.length() > MAX_BYTES) return null
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("v", 0) != VERSION) return null
            if (root.optString("id") != id || root.optInt("signature", Int.MIN_VALUE) != signature) return null
            if (root.optInt("radius", -1) != radiusM) return null
            val savedAt = root.optLong("savedAt", -1L)
            val age = clock() - savedAt
            if (savedAt <= 0L || age < -CLOCK_SLACK_MILLIS || age > TTL_MILLIS) return null
            val array = root.optJSONArray("sights") ?: return null
            val out = ArrayList<Sight>(array.length())
            for (i in 0 until array.length()) {
                val s = array.optJSONObject(i) ?: return null
                val name = s.optString("n")
                val lat = s.optDouble("la", Double.NaN)
                val lon = s.optDouble("lo", Double.NaN)
                if (name.isBlank() || lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                out += Sight(name, lat, lon, s.optInt("k", 0), s.optDouble("e", Double.NaN))
            }
            out.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** Keeps [sights] (found, complete, not empty) for this experience and radius. A failure to write is no failure. */
    fun write(id: String, signature: Int, radiusM: Int, sights: List<Sight>) {
        if (sights.isEmpty()) return
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val array = JSONArray()
            for (s in sights) {
                val o = JSONObject()
                o.put("n", s.name)
                o.put("la", s.latitude)
                o.put("lo", s.longitude)
                o.put("k", s.kind)
                if (!s.elevationM.isNaN()) o.put("e", s.elevationM)
                array.put(o)
            }
            val root = JSONObject()
            root.put("v", VERSION)
            root.put("id", id)
            root.put("signature", signature)
            root.put("radius", radiusM)
            root.put("savedAt", clock())
            root.put("sights", array)
            val target = fileFor(id, signature, radiusM)
            val tmp = File(dir, target.name + ".tmp")
            try {
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) return
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            prune()
        } catch (e: Exception) {
            // The cache is a courtesy to the map servers; the search itself has already succeeded.
        }
    }

    /** Once per process, forgets files that are past their time (and half-written leftovers). */
    private fun prune() {
        if (pruned) return
        pruned = true
        val now = clock()
        for (file in dir.listFiles().orEmpty()) {
            val age = now - file.lastModified()
            if (age > TTL_MILLIS || (file.name.endsWith(".tmp") && age > TMP_MILLIS)) file.delete()
        }
    }

    companion object {
        const val DIR_NAME = "experience-sights"

        /** How long a search is trusted: roads and viewpoints change slowly, the servers are volunteers'. */
        const val TTL_MILLIS = 14L * 24 * 60 * 60 * 1000

        private const val VERSION = 1
        private const val MAX_NAME = 80
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val CLOCK_SLACK_MILLIS = 24L * 60 * 60 * 1000
        private const val TMP_MILLIS = 60L * 60 * 1000
    }
}
