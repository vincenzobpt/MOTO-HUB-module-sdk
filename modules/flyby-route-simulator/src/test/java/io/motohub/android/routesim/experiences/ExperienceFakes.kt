// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.experiences

import io.motohub.android.module.ModuleRouteError
import io.motohub.android.module.ModuleRouteResult
import io.motohub.android.module.ModuleRouting
import io.motohub.android.module.ModuleSightKind
import io.motohub.android.module.ModuleSightResult
import io.motohub.android.module.ModuleSights
import io.motohub.android.routesim.sim.LatLng

/** One degree of latitude, in kilometres. */
private const val KM_PER_DEGREE_LAT = 111.19

/**
 * A router that draws straight lines through the points it is given, [piecesPerLeg] pieces per
 * leg, at [kph]. [handler] sees every route call (numbered from 1) and may answer instead; null
 * means "answer as usual".
 */
class ExpFakeRouting : ModuleRouting {
    val routeCalls = ArrayList<Pair<DoubleArray, DoubleArray>>()
    val preferences = ArrayList<Int>()
    val elevationSizes = ArrayList<Int>()
    var piecesPerLeg = 10
    var kph = 40.0
    var handler: ((Int, DoubleArray, DoubleArray) -> ModuleRouteResult?)? = null
    var throwOnRoute = false
    var throwOnElevation = false
    var elevation: ((DoubleArray, DoubleArray) -> DoubleArray?)? = { lats, _ ->
        DoubleArray(lats.size) { if (it == 2) 1844.4 else 500.0 }
    }

    override fun route(latitudes: DoubleArray, longitudes: DoubleArray, preference: Int): ModuleRouteResult {
        routeCalls += latitudes to longitudes
        preferences += preference
        if (throwOnRoute) throw IllegalStateException("router down")
        return handler?.invoke(routeCalls.size, latitudes, longitudes) ?: usual(latitudes, longitudes)
    }

    fun usual(latitudes: DoubleArray, longitudes: DoubleArray): ModuleRouteResult {
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        lats += latitudes[0]; lons += longitudes[0]
        for (i in 0 until latitudes.size - 1) {
            for (j in 1..piecesPerLeg) {
                val t = j.toDouble() / piecesPerLeg
                lats += latitudes[i] + (latitudes[i + 1] - latitudes[i]) * t
                lons += longitudes[i] + (longitudes[i + 1] - longitudes[i]) * t
            }
        }
        var meters = 0.0
        for (i in 0 until lats.size - 1) {
            meters += LatLng(lats[i], lons[i]).distanceTo(LatLng(lats[i + 1], lons[i + 1]))
        }
        return ModuleRouteResult(
            true, ModuleRouteError.NONE, null, lats.toDoubleArray(), lons.toDoubleArray(),
            meters, meters / (kph / 3.6),
        )
    }

    fun answer(latitudes: DoubleArray, longitudes: DoubleArray, km: Double, minutes: Double) =
        ModuleRouteResult(true, ModuleRouteError.NONE, null, latitudes, longitudes, km * 1000.0, minutes * 60.0)

    fun refuse(kind: Int, message: String? = null) =
        ModuleRouteResult(false, kind, message, DoubleArray(0), DoubleArray(0), 0.0, 0.0)

    override fun speedLimits(latitudes: DoubleArray, longitudes: DoubleArray): FloatArray? = null

    override fun elevations(latitudes: DoubleArray, longitudes: DoubleArray): DoubleArray? {
        elevationSizes += latitudes.size
        if (throwOnElevation) throw IllegalStateException("elevation down")
        return elevation?.invoke(latitudes, longitudes)
    }
}

/** A sights search that answers [result] (or throws) and remembers what it was asked. */
class ExpFakeSights(var result: ModuleSightResult = sightResult(emptyList())) : ModuleSights {
    var calls = 0
    var lastPointCount = 0
    var lastRadius = 0
    var lastKinds = 0
    var throws = false

    override fun along(latitudes: DoubleArray, longitudes: DoubleArray, radiusMeters: Int, kinds: Int): ModuleSightResult {
        calls++
        lastPointCount = latitudes.size
        lastRadius = radiusMeters
        lastKinds = kinds
        if (throws) throw IllegalStateException("overpass down")
        return result
    }
}

fun sightResult(list: List<Sight>, complete: Boolean = true) = ModuleSightResult(
    ok = true,
    error = null,
    complete = complete,
    names = Array(list.size) { list[it].name },
    latitudes = DoubleArray(list.size) { list[it].latitude },
    longitudes = DoubleArray(list.size) { list[it].longitude },
    kinds = IntArray(list.size) { list[it].kind },
    elevations = DoubleArray(list.size) { list[it].elevationM },
)

fun sightFailure(message: String = "Overpass is busy") = ModuleSightResult(
    false, message, false, emptyArray(), DoubleArray(0), DoubleArray(0), IntArray(0), DoubleArray(0),
)

/** The road of the tests: along the 45th parallel from 7.0 E to 7.5 E (about 39 km), a point every 0.01 degree. */
fun testRoad(): List<Pair<Double, Double>> = (0..50).map { 45.0 to 7.0 + it * 0.01 }

/** A sight at longitude [lon], [offKm] kilometres north of the test road. */
fun sightAt(
    name: String,
    lon: Double,
    offKm: Double,
    kind: Int = ModuleSightKind.VIEWPOINT,
    elevationM: Double = Double.NaN,
) = Sight(name, 45.0 + offKm / KM_PER_DEGREE_LAT, lon, kind, elevationM)
