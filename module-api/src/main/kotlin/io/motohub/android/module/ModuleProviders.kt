// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/*
 * Data a module brings into the app's own NAV (contract 32): place icons, tiles over the map,
 * search, drive times. Each is a capability; a module answers the ones it has.
 *
 * Phone only, by promise of the app. What these give is drawn on the phone and nowhere else:
 * never on the motorcycle's screen (Ride Dashboard, Android Auto, a module's projection), never
 * written to the app's own storage (recent places, trip names, saved routes), never handed to
 * another module. A service whose terms allow its data on a phone but not in a vehicle can sit
 * behind these interfaces. Where the rider takes a place further - navigating to it, saving it -
 * the app keeps the coordinates and finds its own name for them.
 *
 * Every call that may reach the network is made off the main thread and may block. A failure is
 * an ordinary answer (empty, null, ok = false), never an exception.
 */

/**
 * Raster tiles a module lays over the NAV map on the phone: traffic, weather, anything drawn as
 * a picture of the map. Listed in the map's layers menu under [title], one switch with the
 * module's [ModuleMapLayer] if it has one too.
 */
interface ModuleMapOverlay {

    /** The switch's name in the layers menu, in the module's words: "Traffic". */
    val title: String

    /** Shown in a corner of the map while the tiles are drawn: "© TomTom". Empty for none. */
    val attribution: String

    /**
     * The tile sets to draw, bottom first, all of them under the map's place icons and labels.
     * [dark] is true when the map is in its night look, for a service with tiles for both.
     * Asked when the layer is switched on and when the look changes; an empty array draws nothing.
     */
    fun tiles(dark: Boolean): Array<ModuleOverlayTiles>
}

class ModuleOverlayTiles(
    /** An https URL with {z}, {x} and {y}: "https://example.com/flow/{z}/{x}/{y}.png?key=…". */
    val urlTemplate: String,
    /** The side of one tile in pixels: 256 or 512. */
    val tileSize: Int,
    /** 0 to 1. */
    val opacity: Float,
    val minZoom: Int,
    val maxZoom: Int
)

/**
 * Place icons from a module's service, offered as a source beside the app's own in the NAV's
 * layers menu ("Place icons: [title]"). The app keeps its chips and categories; the module
 * answers the categories it covers and the app's own sources fill in the rest.
 */
interface ModulePlaceProvider {

    /** The source's name in the layers menu: "TomTom". */
    val title: String

    /** Shown at the foot of a place's sheet: "Place data © TomTom". */
    val attribution: String

    /** The [ModulePoiCategory] names this source can answer. */
    fun categories(): Array<String>

    /**
     * The places of [categories] within [radiusMeters] of ([latitude], [longitude]), nearest
     * first, at most [limit]. Asked when the map settles somewhere new.
     */
    fun nearby(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        categories: Array<String>,
        limit: Int
    ): ModulePoiAnswer

    /**
     * More about one place, asked when the rider opens its sheet: [ModulePoi.tags] to merge
     * over the ones [nearby] gave. Null when there is nothing more.
     */
    fun details(placeId: String): Map<String, String>?
}

/** The categories a [ModulePlaceProvider] can name: the NAV's own chips. */
object ModulePoiCategory {
    const val FUEL = "FUEL"
    const val FOOD = "FOOD"
    const val CAFE = "CAFE"
    const val BAKERY = "BAKERY"
    const val BAR = "BAR"
    const val PARKING = "PARKING"
    const val REST_AREA = "REST_AREA"
    const val MOTORCYCLE_SERVICE = "MOTORCYCLE_SERVICE"
    const val CAR_SERVICE = "CAR_SERVICE"
    const val CHARGING = "CHARGING"
    const val HOTEL = "HOTEL"
    const val CAMPSITE = "CAMPSITE"
    const val VIEWPOINT = "VIEWPOINT"
    const val ATTRACTION = "ATTRACTION"
    const val SUPERMARKET = "SUPERMARKET"
    const val TOILETS = "TOILETS"
    const val ATM = "ATM"
    const val BANK = "BANK"
    const val PHARMACY = "PHARMACY"
    const val HOSPITAL = "HOSPITAL"
    const val POLICE = "POLICE"
    const val POST_OFFICE = "POST_OFFICE"
    const val SHOP = "SHOP"
}

class ModulePoi(
    /** The module's own name for it, handed back to [ModulePlaceProvider.details]. */
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** One of [ModulePoiCategory]. */
    val category: String,
    /**
     * What is known about it in OpenStreetMap's words, so the place sheet reads it as it reads
     * any other: "opening_hours", "phone", "website", "addr:street", "addr:housenumber",
     * "addr:city", "brand", "cuisine", "fuel:diesel". Empty when there is nothing.
     */
    val tags: Map<String, String>
)

class ModulePoiAnswer(
    val ok: Boolean,
    /** True when the service's allowance for the month is used up: the app falls back on its own sources. */
    val exhausted: Boolean,
    val places: Array<ModulePoi>
)

/**
 * A module's search, put before the app's own in the NAV's search box: suggestions while the
 * rider types, results on the keyboard's search key, and the name of a point.
 */
interface ModuleGeocoder {

    /** Shown under the suggestions it gave: "Results from TomTom". */
    val attribution: String

    /** Suggestions for a half-typed [query], nearest to ([nearLatitude], [nearLongitude]) first; NaN for no bias. */
    fun suggest(query: String, nearLatitude: Double, nearLongitude: Double, limit: Int): ModuleGeocodeResult

    /** Results for a finished [query]. */
    fun search(query: String, nearLatitude: Double, nearLongitude: Double, limit: Int): ModuleGeocodeResult

    /** The address or place at a point, for a pin the rider dropped; null when there is none. */
    fun reverse(latitude: Double, longitude: Double): String?
}

class ModuleGeocodeResult(
    val ok: Boolean,
    /** True when the allowance for the month is used up: the app goes on with its own search. */
    val exhausted: Boolean,
    /** The first line of each result: "Rifugio Bonetta". */
    val names: Array<String>,
    /** The second line: "Passo Gavia, 23030 Valfurva SO". Empty where there is none. */
    val addresses: Array<String>,
    val latitudes: DoubleArray,
    val longitudes: DoubleArray
)

/**
 * Drive times from one point to many, for the app to rank places it already has (fuel stations
 * near the route) by time rather than by distance. Never a route to follow: the NAV's own routing
 * draws and guides the way there.
 */
interface ModuleTravelTimes {

    /** Shown beside the times it gave: "Drive times by TomTom". */
    val attribution: String

    /**
     * Seconds by motorcycle from ([fromLatitude], [fromLongitude]) to each destination, with the
     * traffic of now; NaN where there is no way. Null when the service did not answer.
     */
    fun secondsTo(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitudes: DoubleArray,
        toLongitudes: DoubleArray
    ): DoubleArray?
}

/** Where the rider is now, from the phone's own GPS. */
class ModuleFix(
    val latitude: Double,
    val longitude: Double,
    /** Metres per second; NaN when unknown. */
    val speed: Float,
    /** Degrees from north; NaN when unknown. */
    val bearing: Float,
    /** Metres; NaN when unknown. */
    val accuracy: Float,
    /** System.currentTimeMillis() of the fix. */
    val timeMillis: Long
)
