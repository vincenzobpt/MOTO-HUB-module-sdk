// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.mapopt

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.Properties

/**
 * What Mapbox bills this module's use by, with the free monthly allowance (Mapbox's pricing page,
 * October 2026). With Mapbox GL JS the tiles a map draws are part of its map load.
 */
internal enum class Billed(val label: String, val allowance: Int) {
    MAP_LOADS("3D POV map loads", 50_000)
}

/**
 * Map loads this UTC month, counted on this phone: Mapbox's account page has the exact numbers.
 * A new month is a new key in the file; last month's lines are dropped.
 */
internal class Usage(private val file: File?, private val now: () -> Long = { System.currentTimeMillis() }) {

    private val lock = Any()
    private val counts = HashMap<String, Int>()

    init {
        val held = file
        if (held != null && held.isFile) {
            val p = Properties()
            runCatching { held.inputStream().use { p.load(it) } }
            val prefix = month() + "."
            for (name in p.stringPropertyNames()) {
                if (name.startsWith(prefix)) p.getProperty(name).toIntOrNull()?.let { counts[name] = it }
            }
        }
    }

    fun month(): String = Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC)
        .let { String.format(Locale.ROOT, "%04d-%02d", it.year, it.monthValue) }

    fun used(billed: Billed): Int = synchronized(lock) { counts[month() + "." + billed.name] ?: 0 }

    fun add(billed: Billed) {
        synchronized(lock) {
            val key = month() + "." + billed.name
            counts[key] = (counts[key] ?: 0) + 1
            save()
        }
    }

    private fun save() {
        val target = file ?: return
        val prefix = month() + "."
        counts.keys.retainAll { it.startsWith(prefix) }
        val p = Properties()
        counts.forEach { (k, v) -> p.setProperty(k, v.toString()) }
        runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().use { p.store(it, "MapOpt: Mapbox map loads this month") }
        }
    }
}
