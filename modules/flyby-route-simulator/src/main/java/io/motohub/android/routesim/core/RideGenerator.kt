// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Turns the rider's stops into a prepared route (network), a simulated ride (pure) and a saved
// ride (storage). Every method that touches the host blocks: call it off the main thread.
package io.motohub.android.routesim.core

import io.motohub.android.module.ModuleRouteError
import io.motohub.android.module.ModuleRoutePreference
import io.motohub.android.module.ModuleSavedRoute
import io.motohub.android.module.ModuleSimulatedRide
import io.motohub.android.module.MotoHubModuleHost
import io.motohub.android.routesim.sim.LatLng
import io.motohub.android.routesim.sim.RideSimulator
import io.motohub.android.routesim.sim.SimRequest
import io.motohub.android.routesim.sim.SimRoute

class RideGenerator internal constructor(private val services: Services) {

    constructor(host: MotoHubModuleHost) : this(Services.from(host))

    /**
     * The network phase: route, speed limits, elevations and the names of both ends. Needs at
     * least two stops and refuses a route over [MAX_DISTANCE_KM]. The ride is titled "A → B"
     * unless [titleOverride] says otherwise (an experience's name). The road is routed with
     * [preference] (a [ModuleRoutePreference]); the prepared route remembers it.
     */
    fun prepare(
        stops: List<Stop>,
        titleOverride: String? = null,
        preference: Int = ModuleRoutePreference.FASTEST,
        onProgress: (String) -> Unit,
    ): Prepare {
        if (stops.size < 2) return Prepare.Failed(CoreStrings.NEED_TWO_STOPS, ModuleRouteError.OTHER)

        // The shortest way through the stops is never shorter than the straight lines between
        // them: a rider who asks for Rome to Oslo need not wait for the server to say so.
        var crow = 0.0
        for (i in 0 until stops.size - 1) {
            crow += LatLng(stops[i].latitude, stops[i].longitude)
                .distanceTo(LatLng(stops[i + 1].latitude, stops[i + 1].longitude))
        }
        if (crow > MAX_DISTANCE_M) return tooLong(crow)

        onProgress(CoreStrings.PLANNING_ROUTE)
        val routed = try {
            services.routing.route(
                DoubleArray(stops.size) { stops[it].latitude },
                DoubleArray(stops.size) { stops[it].longitude },
                preference,
            )
        } catch (e: Exception) {
            return Prepare.Failed(CoreStrings.ROUTE_FAILED, ModuleRouteError.OTHER)
        }
        if (!routed.ok) {
            val kind = if (routed.errorKind == ModuleRouteError.NONE) ModuleRouteError.OTHER else routed.errorKind
            val message = routed.error?.takeIf { it.isNotBlank() } ?: CoreStrings.kindMessage(kind)
            return Prepare.Failed(message, kind)
        }
        val n = routed.latitudes.size
        if (n < 2 || routed.longitudes.size != n) return Prepare.Failed(CoreStrings.ROUTE_FAILED, ModuleRouteError.OTHER)

        val distance = if (routed.distanceMeters > 0.0 && !routed.distanceMeters.isNaN()) routed.distanceMeters
        else polylineLength(routed.latitudes, routed.longitudes)
        if (distance > MAX_DISTANCE_M) return tooLong(distance)

        onProgress(CoreStrings.FETCHING_LIMITS)
        val fetchedLimits = try {
            services.routing.speedLimits(routed.latitudes, routed.longitudes)
        } catch (e: Exception) {
            null
        }
        val limits = if (fetchedLimits != null && fetchedLimits.size == n - 1) fetchedLimits
        else FloatArray(n - 1) { Float.NaN }

        onProgress(CoreStrings.FETCHING_ELEVATIONS)
        val fetchedAltitudes = try {
            services.routing.elevations(routed.latitudes, routed.longitudes)
        } catch (e: Exception) {
            null
        }
        val altitudes = if (fetchedAltitudes != null && fetchedAltitudes.size == n) fetchedAltitudes
        else DoubleArray(n) { Double.NaN }

        val route = Densify.densify(routed.latitudes, routed.longitudes, altitudes, limits)
        if (route.lats.size < 2) return Prepare.Failed(CoreStrings.ROUTE_EMPTY, ModuleRouteError.OTHER)

        onProgress(CoreStrings.NAMING_PLACES)
        val first = stops.first()
        val last = stops.last()
        val startPlace = nameOf(first)
        val endPlace = nameOf(last)
        val title = (startPlace ?: CoreStrings.coordinates(first.latitude, first.longitude)) + " → " +
            (endPlace ?: CoreStrings.coordinates(last.latitude, last.longitude))

        return Prepare.Ok(
            PreparedRoute(stops.toList(), route, distance, startPlace, endPlace, cleanTitleOverride(titleOverride) ?: title, title, preference),
        )
    }

    /** Pure and fast. The caller picks a new seed for every ride it wants to differ. */
    fun generate(prepared: PreparedRoute, settings: RideSettings, seed: Long): GeneratedRide {
        val output = RideSimulator.run(
            SimRequest(
                route = prepared.route,
                startedAtMillis = settings.startAtMillis,
                profile = settings.profile,
                traffic = settings.traffic,
                seed = seed,
                sampleRateHz = SAMPLE_RATE_HZ,
            ),
        )
        return GeneratedRide(prepared, settings, seed, output)
    }

    fun save(ride: GeneratedRide): Save {
        val o = ride.output
        val n = o.timesMillis.size
        val consistent = n >= 2 && o.latitudes.size == n && o.longitudes.size == n && o.altitudesMeters.size == n &&
            o.speedsKph.size == n && o.leanDegrees.size == n && o.engineRpm.size == n &&
            o.accuracyMeters.size == n && o.satellites.size == n
        if (!consistent) return Save.Failed(CoreStrings.SAVE_INCONSISTENT)
        val simulated = ModuleSimulatedRide(
            ride.prepared.title,
            ride.settings.startAtMillis,
            o.latitudes,
            o.longitudes,
            o.altitudesMeters,
            o.timesMillis,
            o.speedsKph,
            o.leanDegrees,
            o.engineRpm,
            o.accuracyMeters,
            o.satellites,
            ride.prepared.startPlace,
            ride.prepared.endPlace,
        )
        val id = try {
            services.rideWriter.saveSimulatedRide(simulated)
        } catch (e: Exception) {
            null
        }
        return if (id != null) Save.Ok(id) else Save.Failed(CoreStrings.SAVE_FAILED)
    }

    /** Keeps the plan's route among the NAV's simulated routes. Returns its id, or null. */
    fun savePlanAsRoute(plan: Plan, prepared: PreparedRoute): String? {
        val r = prepared.route
        val route = ModuleSavedRoute(
            plan.name.takeIf { it.isNotBlank() } ?: prepared.title,
            r.lats,
            r.lons,
            filledAltitudes(r.altitudesMeters),
            prepared.distanceMeters,
            estimatedSeconds(r, plan.settings),
            prepared.endPlace ?: plan.stops.lastOrNull()?.label ?: "",
        )
        return try {
            services.rideWriter.saveSimulatedRoute(route)
        } catch (e: Exception) {
            null
        }
    }

    /** Opens the saved ride [rideId] in Flyby. False when Flyby is missing or the ride cannot be found. */
    fun openInFlyby(rideId: String): Boolean {
        return try {
            if (!services.modules.isInstalled(FLYBY_ID)) return false
            val entry = services.rides.recordedRides(RECENT_RIDES).firstOrNull { it.id == rideId } ?: return false
            services.modules.openFeatureOn(FLYBY_ID, FLYBY_RIDE_FEATURE, entry)
        } catch (e: Exception) {
            false
        }
    }

    fun flybyInstalled(): Boolean = try {
        services.modules.isInstalled(FLYBY_ID)
    } catch (e: Exception) {
        false
    }

    private fun nameOf(stop: Stop): String? = try {
        services.places.reverse(stop.latitude, stop.longitude)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun tooLong(meters: Double): Prepare =
        Prepare.Failed(CoreStrings.tooLong(Math.round(meters / 1000.0).toInt(), MAX_DISTANCE_KM), ModuleRouteError.TOO_LONG)

    private fun polylineLength(lats: DoubleArray, lons: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until lats.size - 1) sum += LatLng(lats[i], lons[i]).distanceTo(LatLng(lats[i + 1], lons[i + 1]))
        return sum
    }

    /** Empty when nothing is known; otherwise one finite value per point, gaps filled from the nearest known one. */
    private fun filledAltitudes(alt: DoubleArray): DoubleArray {
        if (alt.none { !it.isNaN() }) return DoubleArray(0)
        val out = alt.copyOf()
        var last = Double.NaN
        for (i in out.indices) if (out[i].isNaN()) out[i] = last else last = out[i]
        var next = Double.NaN
        for (i in out.indices.reversed()) if (out[i].isNaN()) out[i] = next else next = out[i]
        return out
    }

    /** What the NAV shows as the planned time: each segment at its limit times the style's factor. */
    private fun estimatedSeconds(r: SimRoute, s: RideSettings): Double {
        var seconds = 0.0
        for (i in 0 until r.lats.size - 1) {
            val len = LatLng(r.lats[i], r.lons[i]).distanceTo(LatLng(r.lats[i + 1], r.lons[i + 1]))
            val limit = r.speedLimitKph[i]
            val kph = if (!limit.isNaN() && limit > 0f) limit * s.profile.overLimitFactor else s.profile.defaultCruiseKph
            seconds += len / (kph.coerceAtLeast(MIN_ESTIMATE_KPH) / 3.6)
        }
        return seconds
    }

    companion object {
        const val MAX_DISTANCE_KM = 500
        private const val MAX_DISTANCE_M = MAX_DISTANCE_KM * 1000.0
        const val SAMPLE_RATE_HZ = 10
        private const val FLYBY_ID = "motohub-flyby"
        private const val FLYBY_RIDE_FEATURE = "flyby-ride"
        private const val RECENT_RIDES = 50
        private const val MIN_ESTIMATE_KPH = 10.0
    }
}
