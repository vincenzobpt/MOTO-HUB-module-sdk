// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties

/**
 * The TomTom APIs the module calls, with the monthly allowance of a free key (TomTom's published
 * pricing, October 2026). Map tiles are not here: TomTom counts them, the phone cannot.
 */
enum class Api(val label: String, val allowance: Int) {
    SUGGEST("Suggestions", 10_000),
    DISCOVER("Place search", 5_000),
    DETAILS("Place details", 5_000),
    // Pins named on the map and the Road info panel: both are TomTom reverse geocoding.
    REVERSE("Reverse lookup", 20_000),
    INCIDENTS("Traffic incidents", 2_500),
    ROUTING("Drive times", 20_000)
}

/**
 * How many calls each API has had this UTC month, counted on this phone: TomTom's dashboard has
 * the exact numbers, this is only enough to stop before the free allowance runs out. A new month
 * is a new key in the file, so nothing is ever reset by hand; last month's lines are dropped on load.
 */
class Usage(private val file: File?, private val now: () -> Long = { System.currentTimeMillis() }) {

    private val lock = Any()
    private val counts = HashMap<String, Int>()

    init {
        val held = file
        if (held != null && held.isFile) {
            val p = Properties()
            runCatching { held.inputStream().use { input -> p.load(input) } }
            val prefix = month() + "."
            for (name in p.stringPropertyNames()) {
                if (name.startsWith(prefix)) p.getProperty(name).toIntOrNull()?.let { counts[name] = it }
            }
        }
    }

    /** "2026-10" in UTC: TomTom's months start there. */
    fun month(): String = Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC).let { String.format(java.util.Locale.ROOT, "%04d-%02d", it.year, it.monthValue) }

    fun used(api: Api): Int = synchronized(lock) { counts[month() + "." + api.name] ?: 0 }

    /** False once the allowance is used up, until the 1st. */
    fun allowed(api: Api): Boolean = used(api) < api.allowance

    /** Counts [calls] on [api]. */
    fun add(api: Api, calls: Int = 1) {
        synchronized(lock) {
            val key = month() + "." + api.name
            counts[key] = (counts[key] ?: 0) + calls
            save()
        }
    }

    /** How many of [wanted] calls the allowance still takes. */
    fun room(api: Api, wanted: Int): Int = minOf(wanted, maxOf(0, api.allowance - used(api)))

    private fun save() {
        val target = file ?: return
        val prefix = month() + "."
        // Only this month is kept; the file never grows.
        counts.keys.retainAll { it.startsWith(prefix) }
        val p = Properties()
        counts.forEach { (k, v) -> p.setProperty(k, v.toString()) }
        runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().use { p.store(it, "TomTom module: calls this month") }
        }
    }
}
