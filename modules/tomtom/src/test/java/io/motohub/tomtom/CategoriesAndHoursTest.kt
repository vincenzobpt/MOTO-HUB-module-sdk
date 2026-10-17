// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import io.motohub.android.module.ModulePoiCategory
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesAndHoursTest {

    @Test
    fun mapsCategoriesToTomTomTypes() {
        assertEquals(listOf("fuel_station"), Categories.typesFor(listOf(ModulePoiCategory.FUEL)))
        assertEquals(listOf("cafe", "coffee_shop", "bar", "pub"), Categories.typesFor(listOf(ModulePoiCategory.CAFE, ModulePoiCategory.BAR)))
        // TomTom has no id for bakeries: the app's own sources answer them.
        assertTrue(Categories.typesFor(listOf(ModulePoiCategory.BAKERY)).isEmpty())
        assertFalse(ModulePoiCategory.BAKERY in Categories.supported())
        assertTrue(ModulePoiCategory.FUEL in Categories.supported())
    }

    @Test
    fun namesTheCategoryOfAResult() {
        val wanted = listOf(ModulePoiCategory.HOTEL, ModulePoiCategory.CAMPSITE)
        assertEquals(ModulePoiCategory.HOTEL, Categories.categoryOf(listOf("motel"), wanted))
        assertEquals(ModulePoiCategory.CAMPSITE, Categories.categoryOf(listOf("caravan_site"), wanted))
        // Unknown ids with two categories asked: no guess.
        assertNull(Categories.categoryOf(listOf("something_else"), wanted))
        // With one asked, the result is that one.
        assertEquals(ModulePoiCategory.FUEL, Categories.categoryOf(listOf("something_else"), listOf(ModulePoiCategory.FUEL)))
    }

    private fun day(date: String, vararg ranges: Pair<String, String>) =
        """{"date":"$date","timeRanges":[${ranges.joinToString(",") { """{"start":"${it.first}","end":"${it.second}","utcOffsetSeconds":7200}""" }}]}"""

    @Test
    fun groupsDaysInOsmSyntax() {
        // 2026-10-12 is a Monday.
        val week = JSONArray(
            "[" + listOf(
                day("2026-10-12", "08:00" to "19:00"), day("2026-10-13", "08:00" to "19:00"), day("2026-10-14", "08:00" to "19:00"),
                day("2026-10-15", "08:00" to "19:00"), day("2026-10-16", "08:00" to "19:00"),
                day("2026-10-17", "08:00" to "13:00"), day("2026-10-18")
            ).joinToString(",") + "]"
        )
        assertEquals("Mo-Fr 08:00-19:00; Sa 08:00-13:00; Su off", OpeningHours.osm(week))
    }

    @Test
    fun splitShiftsAndAlwaysOpen() {
        val split = JSONArray("[" + day("2026-10-12", "09:00" to "12:30", "15:00" to "19:00") + "]")
        assertEquals("Mo 09:00-12:30,15:00-19:00", OpeningHours.osm(split))
        val all = JSONArray("[" + (12..18).joinToString(",") { day("2026-10-$it", "00:00" to "00:00") } + "]")
        assertEquals("24/7", OpeningHours.osm(all))
        assertNull(OpeningHours.osm(JSONArray("[]")))
        assertNull(OpeningHours.osm(null))
    }
}
