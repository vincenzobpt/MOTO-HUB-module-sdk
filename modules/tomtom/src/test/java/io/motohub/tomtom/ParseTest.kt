// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {

    private val discover = """{"results":[
        {"id":"i4gXDo36dRMSvuujipsMMA","type":"poi","title":"Q8 Stelvio","subtitles":["Via Stelvio 3","23032 Bormio","Italy"],
         "distanceInMeters":812,"position":{"type":"Point","coordinates":[10.3713,46.4681]},
         "poiTypes":[{"id":"fuel_station","name":"Petrol Station","groupId":"driving"}],
         "poiBrands":[{"id":"Q8","name":"Q8"}],
         "address":{"street":"Via Stelvio","houseNumber":"3","municipality":"Bormio","postalCode":"23032"},
         "contacts":[{"type":"default","phones":["+39 0342 000000"],"websites":["http://q8.example"]}]},
        {"id":"x2","type":"address","title":"No position here","subtitles":[]},
        {"type":"poi","title":"No id"}]}"""

    @Test
    fun readsHitsAndTheirTags() {
        val hits = Parse.hits(discover)
        assertEquals(2, hits.size)
        val q8 = hits[0]
        assertEquals("Q8 Stelvio", q8.title)
        assertEquals("Via Stelvio 3, 23032 Bormio, Italy", q8.subtitle)
        assertEquals(46.4681, q8.lat!!, 1e-9)
        assertEquals(10.3713, q8.lon!!, 1e-9)
        assertEquals(listOf("fuel_station"), q8.poiTypes)
        assertEquals("Q8", q8.brand)
        assertEquals("Via Stelvio", q8.tags["addr:street"])
        assertEquals("3", q8.tags["addr:housenumber"])
        assertEquals("Bormio", q8.tags["addr:city"])
        assertEquals("+39 0342 000000", q8.tags["phone"])
        assertEquals("http://q8.example", q8.tags["website"])
        assertNull(hits[1].lat)
    }

    @Test
    fun detailsGiveOpeningHoursInOsmWords() {
        val tags = Parse.details(
            """{"id":"p1","type":"poi","title":"Dalbhat","brand":null,
            "poiBrands":[{"id":"X","name":"Dalbhat Group"}],
            "openingHours":[{"date":"2026-10-12","timeRanges":[{"start":"11:00","end":"23:00","utcOffsetSeconds":7200}]}]}"""
        )
        assertEquals("Dalbhat Group", tags["brand"])
        assertEquals("Mo 11:00-23:00", tags["opening_hours"])
    }

    @Test
    fun reverseTakesTheFirstTitle() {
        assertEquals(
            "4320 Payne Avenue, Cleveland, OH 44103",
            Parse.reverse("""{"results":[{"id":"b","type":"address","title":"4320 Payne Avenue, Cleveland, OH 44103","position":{"type":"Point","coordinates":[-81.65,41.51]}}]}""")
        )
        assertNull(Parse.reverse("""{"results":[]}"""))
    }

    @Test
    fun readsIncidents() {
        val list = Parse.incidents(
            """{"incidents":[
            {"type":"Feature","properties":{"id":"4819f7d0","iconCategory":8,"magnitudeOfDelay":4,"startTime":"2026-10-02T15:37:00Z",
              "endTime":"2026-10-30T22:00:00Z","from":"Paleisstraat","to":"Rosmarijnsteeg","length":238.553,"delay":0,"roadNumbers":["SS38"]},
             "geometry":{"type":"LineString","coordinates":[[4.89,52.37],[4.891,52.372],[4.892,52.374]]}},
            {"type":"Feature","properties":{"id":"jam1","iconCategory":6,"magnitudeOfDelay":2,"delay":725,"length":3400,"roadNumbers":[]},
             "geometry":{"type":"Point","coordinates":[10.4,46.5]}},
            {"type":"Feature","properties":{"iconCategory":1},"geometry":{"type":"Point","coordinates":[1,2]}}]}"""
        )
        assertEquals(2, list.size)
        val closure = list[0]
        assertEquals(8, closure.category)
        assertEquals(4, closure.magnitude)
        assertNull(closure.delaySeconds)
        assertEquals(52.372, closure.latitude, 1e-9)
        assertEquals(4.891, closure.longitude, 1e-9)
        assertEquals(listOf("SS38"), closure.roads)
        assertEquals("Paleisstraat", closure.from)
        val jam = list[1]
        assertEquals(725, jam.delaySeconds)
        assertEquals(46.5, jam.latitude, 0.0)
        assertTrue(jam.roads.isEmpty())
    }

    @Test
    fun roadComesFromTheFirstReverseAnswer() {
        val road = Parse.road(
            """{"summary":{"numResults":1},"addresses":[{"address":{"streetName":"Via Stelvio","routeNumbers":["SS38"],
            "speedLimit":"50.00KPH","municipality":"Bormio"},"position":"46.4669,10.3699"}]}"""
        )!!
        assertEquals("Via Stelvio", road.name)
        assertEquals("SS38", road.ref)
        assertEquals(50, road.limitKmh)
    }

    @Test
    fun roadWithoutLimitAndMilesPerHour() {
        val noLimit = Parse.road("""{"addresses":[{"address":{"streetName":"Via Roma"}}]}""")!!
        assertNull(noLimit.limitKmh)
        val mph = Parse.road("""{"addresses":[{"address":{"street":"Main St","speedLimit":"30.00MPH"}}]}""")!!
        assertEquals("Main St", mph.name)
        assertEquals(48, mph.limitKmh)
        assertNull(Parse.road("""{"addresses":[{"address":{"municipality":"Bormio"}}]}"""))
        assertNull(Parse.road("""{"addresses":[]}"""))
    }

    @Test
    fun travelTimeAndErrors() {
        assertEquals(1234.0, Parse.travelSeconds("""{"routes":[{"summary":{"lengthInMeters":9000,"travelTimeInSeconds":1234,"trafficDelayInSeconds":60}}]}""")!!, 0.0)
        assertNull(Parse.travelSeconds("""{"routes":[]}"""))
        assertEquals("Invalid key", Parse.errorWords("""{"detailedError":{"code":"FORBIDDEN","message":"Invalid key"}}"""))
        assertEquals("Forbidden", Parse.errorWords("""{"errorText":"Forbidden","httpStatusCode":403}"""))
        assertNull(Parse.errorWords("not json"))
        assertTrue(JSONObject("""{"a":1}""").has("a"))
    }
}
