// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import io.motohub.android.routesim.experiences.CatalogTestPacks.experienceJson
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExperienceCatalogTest {

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("catalog").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun resources(vararg files: Pair<String, String>): (String) -> InputStream? {
        val map = files.toMap()
        return { path -> map[path]?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) } }
    }

    private fun download(code: String, text: String) {
        File(dir, "experiences").mkdirs()
        File(dir, "experiences/$code.json").writeText(text)
    }

    @Test
    fun bundledPacksAreListedSortedByName() {
        val catalog = ExperienceCatalog(dir, resources(
            "experiences/index.json" to CatalogTestPacks.indexJson(),
            "experiences/it.json" to CatalogTestPacks.goodPack("IT", 1, 12, "Italy"),
            "experiences/at.json" to CatalogTestPacks.goodPack("AT", 1, 11, "Austria"),
            "experiences/fr.json" to CatalogTestPacks.goodPack("FR", 1, 10, "France"),
        ))
        assertEquals(
            listOf(CountryInfo("AT", "Austria", 11), CountryInfo("FR", "France", 10), CountryInfo("IT", "Italy", 12)),
            catalog.countries(),
        )
        assertEquals(12, catalog.pack("IT")?.experiences?.size)
        assertEquals(12, catalog.pack("it")?.experiences?.size)
        assertNull(catalog.pack("DE"))
    }

    @Test
    fun missingOrPlaceholderResourcesGiveAnEmptyCatalogue() {
        assertTrue(ExperienceCatalog(dir, resources()).countries().isEmpty())
        assertTrue(ExperienceCatalog(dir, resources("experiences/index.json" to CatalogTestPacks.indexJson())).countries().isEmpty())
        assertTrue(ExperienceCatalog(dir, { null }).countries().isEmpty())
        assertTrue(ExperienceCatalog(dir, { throw java.io.IOException("boom") }).countries().isEmpty())
    }

    @Test
    fun aCountryOutsideTheDefaultListIsFoundThroughTheIndex() {
        val catalog = ExperienceCatalog(dir, resources(
            "experiences/index.json" to CatalogTestPacks.indexJson(CatalogTestPacks.IndexLine("NO", "Norway", 1, "c".repeat(64), 1)),
            "experiences/no.json" to CatalogTestPacks.goodPack("NO", 1, 10, "Norway"),
        ))
        assertEquals(listOf("NO"), catalog.countries().map { it.code })
    }

    @Test
    fun aShortPackIsStillReturned() {
        val catalog = ExperienceCatalog(dir, resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 1, 3)))
        assertEquals(3, catalog.pack("IT")?.experiences?.size)
        assertEquals(3, catalog.countries().single().experienceCount)
    }

    @Test
    fun higherDownloadedVersionWins() {
        download("it", CatalogTestPacks.goodPack("IT", 5, 14, "Italia"))
        val catalog = ExperienceCatalog(dir, resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 2, 12)))
        assertEquals(5, catalog.pack("IT")?.version)
        assertEquals(14, catalog.pack("IT")?.experiences?.size)
        assertEquals("Italia", catalog.countries().single().name)
    }

    @Test
    fun higherBundledVersionWinsAfterAnAppUpdate() {
        download("it", CatalogTestPacks.goodPack("IT", 2, 14))
        val catalog = ExperienceCatalog(dir, resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 4, 12)))
        assertEquals(4, catalog.pack("IT")?.version)
        assertEquals(12, catalog.pack("IT")?.experiences?.size)
    }

    @Test
    fun onATieTheBundledPackWins() {
        download("it", CatalogTestPacks.goodPack("IT", 3, 14))
        val catalog = ExperienceCatalog(dir, resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 3, 12)))
        assertEquals(12, catalog.pack("IT")?.experiences?.size)
    }

    @Test
    fun aDamagedDownloadIsIgnored() {
        val bundled = resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 2, 12))
        for (damaged in listOf("", "{\"schema\":1,", "garbage", CatalogTestPacks.goodPack("IT", 9, 12).dropLast(5))) {
            download("it", damaged)
            val catalog = ExperienceCatalog(dir, bundled)
            assertEquals(damaged, 2, catalog.pack("IT")?.version)
        }
    }

    @Test
    fun aDownloadForTheWrongCountryOrWithNothingValidIsIgnored() {
        val bundled = resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 2, 12))
        download("it", CatalogTestPacks.goodPack("FR", 9, 12))
        assertEquals(2, ExperienceCatalog(dir, bundled).pack("IT")?.version)
        download("it", CatalogTestPacks.packJson("IT", 9, listOf(experienceJson("it-bad") { put("km", 1) })))
        assertEquals(2, ExperienceCatalog(dir, bundled).pack("IT")?.version)
    }

    @Test
    fun aDownloadedCountryWithNoBundledPackIsListed() {
        download("pt", CatalogTestPacks.goodPack("PT", 1, 10, "Portugal"))
        assertEquals(listOf("PT"), ExperienceCatalog(dir, resources()).countries().map { it.code })
    }

    @Test
    fun invalidExperiencesInsideAPackAreLeftOut() {
        val text = CatalogTestPacks.packJson("IT", 1, listOf(experienceJson("it-a"), experienceJson("it-b") { put("km", 0) }))
        assertEquals(1, ExperienceCatalog(dir, resources("experiences/it.json" to text)).pack("IT")?.experiences?.size)
    }

    @Test
    fun resultsAreCachedUntilReload() {
        var served = CatalogTestPacks.goodPack("IT", 1, 10)
        var reads = 0
        val catalog = ExperienceCatalog(dir, { path ->
            if (path == "experiences/it.json") { reads++; ByteArrayInputStream(served.toByteArray()) } else null
        })
        assertEquals(1, catalog.pack("IT")?.version)
        assertEquals(1, catalog.pack("IT")?.version)
        catalog.countries()
        assertEquals(1, reads)

        served = CatalogTestPacks.goodPack("IT", 2, 10)
        assertEquals(1, catalog.pack("IT")?.version)
        catalog.reload()
        assertEquals(2, catalog.pack("IT")?.version)
        assertEquals(2, reads)
    }

    @Test
    fun reloadPicksUpANewDownload() {
        val catalog = ExperienceCatalog(dir, resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 1, 10)))
        assertEquals(1, catalog.pack("IT")?.version)
        download("it", CatalogTestPacks.goodPack("IT", 2, 10))
        assertEquals(1, catalog.pack("IT")?.version)
        catalog.reload()
        assertEquals(2, catalog.pack("IT")?.version)
    }

    @Test
    fun readsFromManyThreads() {
        val catalog = ExperienceCatalog(dir, resources("experiences/it.json" to CatalogTestPacks.goodPack("IT", 1, 12)))
        val sizes = java.util.Collections.synchronizedList(ArrayList<Int>())
        val threads = (1..8).map { n ->
            Thread { repeat(50) { sizes += catalog.pack("IT")?.experiences?.size ?: -1; if (n % 3 == 0) catalog.reload() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(400, sizes.size)
        assertTrue(sizes.all { it == 12 })
        assertNotNull(catalog.pack("IT"))
    }

    @Test
    fun theDefaultResourceLoaderCanReadTheBundledIndex() {
        // The real class loader, with whatever the module has under src/main/resources.
        val catalog = ExperienceCatalog(dir)
        catalog.countries()
        assertTrue(catalog.countries().all { it.experienceCount >= 1 })
    }
}
