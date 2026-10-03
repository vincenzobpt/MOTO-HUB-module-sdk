// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.module.ModuleBridge
import io.motohub.android.module.ModuleExtensionEntry
import io.motohub.android.module.ModulePlaceResult
import io.motohub.android.module.ModulePlaces
import io.motohub.android.module.ModuleRideEntry
import io.motohub.android.module.ModuleRideLibrary
import io.motohub.android.module.ModuleRideTrack
import io.motohub.android.module.ModuleRideWriter
import io.motohub.android.module.ModuleRouteError
import io.motohub.android.module.ModuleRouteResult
import io.motohub.android.module.ModuleRouting
import io.motohub.android.module.ModuleSavedRoute
import io.motohub.android.module.ModuleSimulatedRide

class FakeRouting : ModuleRouting {
    var result: ModuleRouteResult? = null
    var limits: FloatArray? = null
    var elevation: DoubleArray? = null
    var routeCalls = 0
    var lastPreference = -1
    var lastRouteLats = DoubleArray(0)
    var lastRouteLons = DoubleArray(0)

    override fun route(latitudes: DoubleArray, longitudes: DoubleArray, preference: Int): ModuleRouteResult {
        routeCalls++
        lastPreference = preference
        lastRouteLats = latitudes
        lastRouteLons = longitudes
        return result ?: throw IllegalStateException("no route result set")
    }

    override fun speedLimits(latitudes: DoubleArray, longitudes: DoubleArray): FloatArray? = limits
    override fun elevations(latitudes: DoubleArray, longitudes: DoubleArray): DoubleArray? = elevation
}

class FakePlaces : ModulePlaces {
    /** Name per "lat,lon" key as given to [name]; absent = null. */
    private val names = HashMap<String, String>()
    fun name(lat: Double, lon: Double, value: String) { names["$lat,$lon"] = value }
    override fun search(query: String, nearLatitude: Double, nearLongitude: Double, limit: Int): ModulePlaceResult =
        throw NotImplementedError()
    override fun reverse(latitude: Double, longitude: Double): String? = names["$latitude,$longitude"]
}

class FakeRideWriter : ModuleRideWriter {
    var savedRide: ModuleSimulatedRide? = null
    var savedRoute: ModuleSavedRoute? = null
    var rideId: String? = "ride-1"
    var routeId: String? = "route-1"
    override fun saveSimulatedRide(ride: ModuleSimulatedRide): String? { savedRide = ride; return rideId }
    override fun saveSimulatedRoute(route: ModuleSavedRoute): String? { savedRoute = route; return routeId }
    override fun removeSimulatedRoute(id: String): Boolean = throw NotImplementedError()
}

class FakeBridge : ModuleBridge {
    val installed = HashSet<String>()
    var openedModule: String? = null
    var openedFeature: String? = null
    var openedEntry: ModuleRideEntry? = null
    var openResult = true
    override fun isInstalled(moduleId: String): Boolean = moduleId in installed
    override fun openFeatureOn(moduleId: String, featureId: String, entry: ModuleRideEntry): Boolean {
        openedModule = moduleId; openedFeature = featureId; openedEntry = entry
        return openResult
    }
    override fun extensions(): List<ModuleExtensionEntry> = throw NotImplementedError()
    override fun openExtension(entry: ModuleExtensionEntry): Unit = throw NotImplementedError()
}

class FakeRides : ModuleRideLibrary {
    var recorded: List<ModuleRideEntry> = emptyList()
    override fun recordedRides(limit: Int): List<ModuleRideEntry> = recorded.take(limit)
    override fun savedRoutes(limit: Int): List<ModuleRideEntry> = throw NotImplementedError()
    override fun activeRoute(): ModuleRideEntry? = throw NotImplementedError()
    override fun track(entry: ModuleRideEntry, maxPoints: Int): ModuleRideTrack? = throw NotImplementedError()
    override fun engineRpm(track: ModuleRideTrack): FloatArray? = throw NotImplementedError()
    override fun isSimulated(entry: ModuleRideEntry): Boolean = throw NotImplementedError()
}

class Rig {
    val routing = FakeRouting()
    val places = FakePlaces()
    val writer = FakeRideWriter()
    val bridge = FakeBridge()
    val rides = FakeRides()
    val generator = RideGenerator(Services(routing, places, writer, bridge, rides))

    fun ok(lats: DoubleArray, lons: DoubleArray, distance: Double) {
        routing.result = ModuleRouteResult(true, ModuleRouteError.NONE, null, lats, lons, distance, 0.0)
    }

    fun fail(kind: Int, message: String?) {
        routing.result = ModuleRouteResult(false, kind, message, DoubleArray(0), DoubleArray(0), 0.0, 0.0)
    }
}

/** A start and a destination about 4 km apart along a parallel at 45 N. */
val SHORT_STOPS = listOf(Stop(45.0, 7.00, "A"), Stop(45.0, 7.05, "B"))
