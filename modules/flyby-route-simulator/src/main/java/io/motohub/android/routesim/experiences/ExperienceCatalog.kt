// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The catalogue as the page sees it: the packs inside the module and the ones downloaded later, the
// newer of each country's two. Plain Kotlin, no Android.
package io.motohub.android.routesim.experiences

import java.io.File
import java.io.InputStream

/** What the country selector needs to list a country without holding its experiences. */
data class CountryInfo(val code: String, val name: String, val experienceCount: Int)

/**
 * Reads `experiences/index.json` and `experiences/<cc>.json` from the module's resources (the packs it
 * shipped with) and `<storageDir>/experiences/<cc>.json` (the packs [CatalogUpdater] downloaded). For
 * each country the higher `version` wins; on a tie the bundled copy does, being the one known good.
 * A downloaded file that cannot be read is ignored, as if it were not there.
 *
 * Everything is read once and kept; [reload] reads again, after an update. [openResource] is the
 * module's class loader by default: the app loads the module through a DexClassLoader, which serves
 * the non-class entries of the module's zip.
 */
class ExperienceCatalog(
    private val storageDir: File,
    private val openResource: (String) -> InputStream? = { path ->
        ExperienceCatalog::class.java.classLoader?.getResourceAsStream(path)
    },
) {

    private val lock = Any()
    private var packs: Map<String, CountryPack>? = null

    /** Countries that have at least one valid experience, sorted by name. */
    fun countries(): List<CountryInfo> = loaded().values
        .map { CountryInfo(it.country, it.name, it.experiences.size) }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.code }))

    /** The country's pack, or null when there is none. A pack with few experiences is still returned. */
    fun pack(country: String): CountryPack? = loaded()[country.uppercase()]

    /** Forgets what was read, so the next call reads the resources and the disk again. */
    fun reload() = synchronized(lock) { packs = null }

    private fun loaded(): Map<String, CountryPack> = synchronized(lock) {
        packs ?: load().also { packs = it }
    }

    private fun load(): Map<String, CountryPack> {
        val codes = LinkedHashSet<String>(CATALOG_COUNTRIES)
        readResource(INDEX_PATH)?.let { text -> CatalogJson.parseIndex(text).packs.forEach { codes += it.country } }
        downloadedDir().listFiles()?.forEach { file ->
            val code = file.name.removeSuffix(".json").uppercase()
            if (file.isFile && file.name.endsWith(".json") && code.length == 2 && code.all { it in 'A'..'Z' }) codes += code
        }

        val result = LinkedHashMap<String, CountryPack>()
        for (code in codes) {
            val bundled = readPack(readResource("$RESOURCE_DIR/${code.lowercase()}.json"), code)
            val downloaded = readPack(readFile(File(downloadedDir(), "${code.lowercase()}.json")), code)
            val best = when {
                bundled == null -> downloaded
                downloaded == null -> bundled
                downloaded.version > bundled.version -> downloaded
                else -> bundled
            }
            if (best != null) result[code] = best
        }
        return result
    }

    /** The pack when the text is a readable pack of [code] with something in it, otherwise null. */
    private fun readPack(text: String?, code: String): CountryPack? {
        if (text == null) return null
        val pack = CatalogJson.parsePack(text).pack
        return pack.takeIf { it.country == code && it.experiences.isNotEmpty() }
    }

    private fun readResource(path: String): String? = try {
        openResource(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private fun readFile(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    private fun downloadedDir() = File(storageDir, RESOURCE_DIR)

    private companion object {
        const val RESOURCE_DIR = "experiences"
        const val INDEX_PATH = "$RESOURCE_DIR/index.json"
    }
}
