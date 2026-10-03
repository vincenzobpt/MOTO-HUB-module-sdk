// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchInputTest {
    private fun check(text: String, lat: Double, lon: Double) {
        val p = SearchInput.parseLatLon(text)
        assertNotNull(text, p)
        assertEquals(text, lat, p!!.lat, 1e-9)
        assertEquals(text, lon, p.lon, 1e-9)
    }

    @Test
    fun parsesLatLonInTheFormsPeoplePaste() {
        check("45.0703, 7.6869", 45.0703, 7.6869)
        check("45.0703,7.6869", 45.0703, 7.6869)
        check("45.0703 7.6869", 45.0703, 7.6869)
        check("  45.0703 ,  7.6869  ", 45.0703, 7.6869)
        check("45.0703; 7.6869", 45.0703, 7.6869)
        check("-33.8688, 151.2093", -33.8688, 151.2093)
        check("45, 7", 45.0, 7.0)
    }

    @Test
    fun parsesDecimalCommas() {
        check("45,0703 7,6869", 45.0703, 7.6869)
        check("45,0703; 7,6869", 45.0703, 7.6869)
        check("45,0703, 7,6869", 45.0703, 7.6869)
    }

    @Test
    fun refusesWhatIsNotAPointOnEarth() {
        for (text in listOf("", "   ", "Torino", "45.0703", "12", "91, 10", "10, 181", "-91, 0", "1,2,3", "45.0703, abc", "NaN, 1", "1e3, 5")) {
            assertNull(text, SearchInput.parseLatLon(text))
        }
    }

    @Test
    fun searchesFromThreeCharacters() {
        assertFalse(SearchInput.isSearchable(""))
        assertFalse(SearchInput.isSearchable("ab"))
        assertFalse(SearchInput.isSearchable("  ab "))
        assertTrue(SearchInput.isSearchable("abc"))
        assertTrue(SearchInput.isSearchable(" Sestriere "))
        assertEquals(700L, SearchInput.DEBOUNCE_MS)
    }
}
