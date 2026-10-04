// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryLocatorTest {

    private fun check(expected: String?, vararg places: Triple<String, Double, Double>) {
        places.forEach { (name, lat, lon) -> assertEquals(name, expected, CountryLocator.locate(lat, lon)) }
    }

    private fun at(name: String, lat: Double, lon: Double) = Triple(name, lat, lon)

    @Test fun italy() = check("IT",
        at("Bolzano", 46.50, 11.35), at("Milan", 45.46, 9.19), at("Rome", 41.90, 12.50), at("Naples", 40.85, 14.27),
        at("Palermo", 38.12, 13.36), at("Cagliari", 39.22, 9.12), at("Venice", 45.44, 12.33), at("Trieste", 45.65, 13.77),
        at("Aosta", 45.74, 7.32), at("Livigno", 46.54, 10.14), at("Cortina", 46.54, 12.14), at("Merano", 46.67, 11.16),
        at("Bari", 41.12, 16.87), at("Reggio Calabria", 38.11, 15.65), at("Genoa", 44.41, 8.93), at("Turin", 45.07, 7.69),
        at("Ventimiglia", 43.79, 7.60), at("Como", 45.81, 9.08), at("Tarvisio", 46.50, 13.58), at("Olbia", 40.92, 9.50),
    )

    @Test fun france() = check("FR",
        at("Nice", 43.70, 7.27), at("Paris", 48.86, 2.35), at("Strasbourg", 48.58, 7.75), at("Lyon", 45.76, 4.84),
        at("Chamonix", 45.92, 6.87), at("Bordeaux", 44.84, -0.58), at("Perpignan", 42.70, 2.89), at("Brest", 48.39, -4.49),
        at("Evian", 46.40, 6.59), at("Annecy", 45.90, 6.13), at("Ajaccio", 41.92, 8.74), at("Lille", 50.63, 3.06),
    )

    @Test fun switzerland() = check("CH",
        at("Zurich", 47.37, 8.54), at("Geneva", 46.20, 6.14), at("Lugano", 46.00, 8.95), at("Basel", 47.56, 7.59),
        at("Bern", 46.95, 7.45), at("St Moritz", 46.49, 9.84), at("Zermatt", 46.02, 7.75), at("Lausanne", 46.52, 6.63),
    )

    @Test fun austria() = check("AT",
        at("Salzburg", 47.80, 13.04), at("Innsbruck", 47.26, 11.39), at("Vienna", 48.21, 16.37), at("Graz", 47.07, 15.44),
        at("Klagenfurt", 46.62, 14.31), at("Bregenz", 47.50, 9.75), at("Lienz", 46.83, 12.77), at("Obergurgl", 46.87, 11.03),
    )

    @Test fun germany() = check("DE",
        at("Munich", 48.14, 11.58), at("Berlin", 52.52, 13.40), at("Hamburg", 53.55, 10.00), at("Cologne", 50.94, 6.96),
        at("Freiburg", 47.99, 7.85), at("Konstanz", 47.66, 9.17), at("Garmisch", 47.49, 11.09), at("Berchtesgaden", 47.63, 13.00),
        at("Passau", 48.57, 13.43), at("Dresden", 51.05, 13.74), at("Kiel", 54.32, 10.14),
    )

    @Test fun spain() = check("ES",
        at("Madrid", 40.42, -3.70), at("Barcelona", 41.39, 2.17), at("Seville", 37.39, -5.99), at("Bilbao", 43.26, -2.93),
        at("Valencia", 39.47, -0.38), at("Malaga", 36.72, -4.42), at("Vigo", 42.24, -8.72), at("Palma", 39.57, 2.65),
        at("Girona", 41.98, 2.82), at("Badajoz", 38.88, -6.97),
    )

    @Test fun portugal() = check("PT",
        at("Lisbon", 38.72, -9.14), at("Porto", 41.15, -8.61), at("Faro", 37.02, -7.93), at("Braga", 41.55, -8.42),
        at("Evora", 38.57, -7.91), at("Coimbra", 40.21, -8.43), at("Elvas", 38.88, -7.16), at("Braganca", 41.81, -6.76),
    )

    @Test fun slovenia() = check("SI",
        at("Ljubljana", 46.05, 14.51), at("Maribor", 46.55, 15.65), at("Koper", 45.55, 13.73), at("Bled", 46.37, 14.11),
        at("Kranjska Gora", 46.48, 13.79), at("Novo Mesto", 45.80, 15.17),
    )

    @Test fun croatia() = check("HR",
        at("Zagreb", 45.81, 15.98), at("Split", 43.51, 16.44), at("Dubrovnik", 42.65, 18.09), at("Pula", 44.87, 13.85),
        at("Rijeka", 45.33, 14.44), at("Osijek", 45.55, 18.69), at("Zadar", 44.12, 15.23),
    )

    @Test fun elsewhereIsNull() = check(null,
        at("Budapest", 47.50, 19.04), at("Prague", 50.08, 14.44), at("London", 51.51, -0.13), at("Brussels", 50.85, 4.35),
        at("Tangier", 35.76, -5.80), at("Valletta", 35.90, 14.51), at("Belgrade", 44.79, 20.45), at("Warsaw", 52.2, 21.0),
        at("Origin", 0.0, 0.0), at("New York", 40.71, -74.0), at("Copenhagen", 55.68, 12.57),
    )

    @Test fun nonsenseInputIsNull() {
        assertNull(CountryLocator.locate(Double.NaN, 10.0))
        assertNull(CountryLocator.locate(45.0, Double.NaN))
        assertNull(CountryLocator.locate(200.0, 400.0))
    }

    @Test fun catalogCountriesAreTheNine() {
        assertEquals(setOf("IT", "FR", "CH", "AT", "DE", "ES", "PT", "SI", "HR"), CATALOG_COUNTRIES.toSet())
        assertEquals(9, CATALOG_COUNTRIES.size)
    }
}
