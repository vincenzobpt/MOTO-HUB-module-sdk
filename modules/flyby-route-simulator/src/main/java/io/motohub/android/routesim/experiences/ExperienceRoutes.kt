// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Builds the stops of an experience with its detours, through the host's routing and sights.
// Every method that touches the host blocks: call it off the main thread.
package io.motohub.android.routesim.experiences

import io.motohub.android.module.ModuleRouteError
import io.motohub.android.module.ModuleRoutePreference
import io.motohub.android.module.ModuleRouteResult
import io.motohub.android.module.ModuleRouting
import io.motohub.android.module.ModuleSightKind
import io.motohub.android.module.ModuleSights
import io.motohub.android.module.MotoHubModuleHost
import io.motohub.android.routesim.core.CoreStrings
import io.motohub.android.routesim.core.Stop
import io.motohub.android.routesim.sim.LatLng
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** The plain route of an experience, A through its via points to B, without detours. */
class BaseRoute(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val km: Double,
    val minutes: Double,
) {
    /** The road as (latitude, longitude) pairs. */
    fun polyline(): List<Pair<Double, Double>> = List(latitudes.size) { latitudes[it] to longitudes[it] }

    /** Filled in by the first build that asked the elevation service. */
    internal var maxElevationM: Int? = null
    internal var elevationAsked = false
}

/**
 * An experience as it will be ridden. [stops] are A, the detours and the via points in the order the
 * road meets them, then B (a core [Stop] each, ready for the planner). [km] and [minutes] are the
 * router's for exactly these stops. [complete] is false when the sights search did not cover the
 * whole road. [note] is a sentence for the rider when something degraded; [error] is true when
 * there is no route at all (then [stops] are the plain ones and [km]/[minutes] the catalogue's).
 */
class ExperienceRoute(
    val stops: List<Stop>,
    val detours: List<Sight>,
    val km: Double,
    val minutes: Double,
    val maxElevationM: Int?,
    val complete: Boolean,
    val note: String?,
    val error: Boolean,
)

/**
 * Turns an experience and the rider's filters into a route: the plain A to B road is routed, the
 * sights near it are asked for, [DetourPlanner] picks the passages, and the stops are routed again for
 * the real km and time. Results are cached in memory (the plain road per experience, the finished
 * route per experience and the filters that matter), for the life of the process.
 *
 * Calls to the host are made one after another, never in parallel: the app paces the routing server
 * and the sights search, and a burst would only be refused.
 *
 * Politeness: the sights come from public, volunteer-run map servers that may refuse a rider for
 * hours. Found sights are kept on disk ([storageDir], 14 days) so an app start does not repeat the
 * search; after a search that failed, timed out or found nothing, the host is not asked again for
 * [BREAKER_MILLIS] (a circuit breaker, process-wide; [resetBreaker] is the rider's "Try again"), and
 * the plain route is answered at once. [clock] is injectable for tests.
 */
class ExperienceRoutes(
    private val routing: ModuleRouting,
    private val sights: ModuleSights,
    private val radiusM: Int = DetourPlanner.DEFAULT_RADIUS_M,
    storageDir: File? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    constructor(host: MotoHubModuleHost) : this(host.routing, host.sights)

    private val diskCache: SightsDiskCache? = storageDir?.let { SightsDiskCache(it, clock) }

    /** True while the host's sights search is being left alone after a failure. */
    fun isBreakerOpen(): Boolean = clock() < breakerUntil.get()

    /** Forgets that the search failed, so the next build asks the host again: the rider's "Try again". */
    fun resetBreaker() {
        breakerUntil.set(Long.MIN_VALUE)
    }

    private fun openBreaker() {
        breakerUntil.set(clock() + BREAKER_MILLIS)
    }

    /** The plain route of [experience] (A, via points, B; scenic), or null when it could not be had. */
    fun base(experience: Experience): BaseRoute? =
        (fetchBase(experience) as? BaseOutcome.Ok)?.route

    /** Never throws. See [ExperienceRoute]. [alt] is 0 for the best detours, n for the n-th alternative. */
    fun build(experience: Experience, filters: ExperienceFilters, alt: Int): ExperienceRoute = try {
        buildChecked(experience, filters, alt)
    } catch (e: Exception) {
        failed(experience, RouteNotes.UNEXPECTED)
    }

    private fun buildChecked(experience: Experience, filters: ExperienceFilters, alt: Int): ExperienceRoute {
        val detours = filters.detours.coerceIn(0, ExperienceFilters.MAX_DETOURS)
        val identity = identityOf(experience)
        val key = ResultKey(identity, detours, filters.culture, filters.maxKm, filters.maxMinutes, alt.coerceAtLeast(0))
        cachedResult(key)?.let { return it }

        val baseOutcome = fetchBase(experience)
        val base = when (baseOutcome) {
            is BaseOutcome.Ok -> baseOutcome.route
            is BaseOutcome.Failed -> return failed(experience, baseOutcome.message)
        }
        val baseStops = baseStops(experience)

        if (detours == 0) {
            return plain(base, baseStops, complete = true, note = null).also { store(key, it) }
        }

        if (base.km > filters.maxKm || base.minutes > filters.maxMinutes) {
            // The plain road is already longer than the rider wants: no detour can help, no need to search.
            return plain(base, baseStops, complete = true, note = RouteNotes.BASE_OVER_LIMIT).also { store(key, it) }
        }

        val found = findSights(experience, identity, base)
        if (found == null) {
            // The plain road is still a good answer; not cached, so the next call tries the sights again.
            // A search that came back with nothing at all counts as unavailable too: the host turns
            // chunks it could not fetch into an empty list, and a corridor with truly no sight is rare.
            return plain(base, baseStops, complete = true, note = RouteNotes.SIGHTS_UNAVAILABLE)
        }

        val road = base.polyline()
        // The start, the via points and the end are already stops of the ride: never offered as detours.
        val avoid = listOf(experience.from) + experience.via + experience.to
        var chosen = DetourPlanner.planRanked(
            road, found.sights, filters, base.km, base.minutes, alt.coerceAtLeast(0), radiusM, avoid,
        )
        val completeNote = if (found.complete) null else RouteNotes.PARTIAL_SEARCH
        if (chosen.isEmpty()) {
            val note = completeNote ?: RouteNotes.NO_DETOURS
            val result = plain(base, baseStops, found.complete, note)
            // Sights were found and none passed the planner: that verdict is remembered, unless the
            // search was partial (the place that would have passed may be in the part that failed).
            if (found.complete) store(key, result)
            return result
        }

        var retries = 0
        var failedLast = false
        while (chosen.isNotEmpty()) {
            val merged = mergeStops(experience, base, chosen)
            val stops = merged.stops
            val routed = routeStops(stops)
            if (routed is StopsOutcome.Routed) {
                val kmOver = routed.km > filters.maxKm && base.km <= filters.maxKm
                val minOver = routed.minutes > filters.maxMinutes && base.minutes <= filters.maxMinutes
                if (!kmOver && !minOver) {
                    val result = ExperienceRoute(
                        stops = stops,
                        detours = merged.detours,
                        km = routed.km,
                        minutes = routed.minutes,
                        maxElevationM = maxElevation(routed.latitudes, routed.longitudes),
                        complete = found.complete,
                        note = completeNote,
                        error = false,
                    )
                    store(key, result)
                    return result
                }
                failedLast = false
            } else if (routed is StopsOutcome.Failed) {
                failedLast = true
                if (routed.kind == ModuleRouteError.NO_NETWORK) break   // another try would fail the same way
            }
            if (retries >= MAX_RETRIES) break
            retries++
            chosen = chosen.dropLast(1)   // the lowest-ranked pick goes first
        }

        // No set of detours fitted or routed: the plain road, with the reason.
        val note = if (failedLast) RouteNotes.DETOURS_FAILED else RouteNotes.DETOURS_TOO_LONG
        val fallback = plain(base, baseStops, found.complete, note)
        // A transient failure is not remembered; a budget verdict is.
        if (!failedLast) store(key, fallback)
        return fallback
    }

    // --- host calls ---------------------------------------------------------------------------

    private sealed class BaseOutcome {
        class Ok(val route: BaseRoute) : BaseOutcome()
        class Failed(val message: String) : BaseOutcome()
    }

    private fun fetchBase(experience: Experience): BaseOutcome {
        val identity = identityOf(experience)
        cachedBase(identity)?.let { return BaseOutcome.Ok(it) }
        val stops = baseStops(experience)
        return when (val routed = routeStops(stops)) {
            is StopsOutcome.Routed -> {
                val route = BaseRoute(routed.latitudes, routed.longitudes, routed.km, routed.minutes)
                synchronized(baseCache) {
                    // A catalogue update under the same id replaces the old road for good.
                    baseCache.keys.removeAll { it.id == identity.id && it != identity }
                    baseCache[identity] = route
                }
                BaseOutcome.Ok(route)
            }
            is StopsOutcome.Failed -> BaseOutcome.Failed(routed.message)
        }
    }

    private sealed class StopsOutcome {
        class Routed(
            val latitudes: DoubleArray,
            val longitudes: DoubleArray,
            val km: Double,
            val minutes: Double,
        ) : StopsOutcome()

        class Failed(val message: String, val kind: Int) : StopsOutcome()
    }

    private fun routeStops(stops: List<Stop>): StopsOutcome {
        val routed: ModuleRouteResult = try {
            routing.route(
                DoubleArray(stops.size) { stops[it].latitude },
                DoubleArray(stops.size) { stops[it].longitude },
                ModuleRoutePreference.SCENIC,
            )
        } catch (e: Exception) {
            return StopsOutcome.Failed(CoreStrings.ROUTE_FAILED, ModuleRouteError.OTHER)
        }
        if (!routed.ok) {
            val kind = if (routed.errorKind == ModuleRouteError.NONE) ModuleRouteError.OTHER else routed.errorKind
            return StopsOutcome.Failed(messageOf(kind), kind)
        }
        val n = routed.latitudes.size
        if (n < 2 || routed.longitudes.size != n) {
            return StopsOutcome.Failed(CoreStrings.ROUTE_FAILED, ModuleRouteError.OTHER)
        }
        val meters = if (routed.distanceMeters > 0.0 && !routed.distanceMeters.isNaN()) routed.distanceMeters
        else polylineLengthM(routed.latitudes, routed.longitudes)
        val seconds = if (routed.durationSeconds > 0.0 && !routed.durationSeconds.isNaN()) routed.durationSeconds
        else meters / (FALLBACK_KPH / 3.6)
        return StopsOutcome.Routed(routed.latitudes, routed.longitudes, meters / 1000.0, seconds / 60.0)
    }

    private class FoundSights(val sights: List<Sight>, val complete: Boolean)

    /**
     * The sights near [base]'s road. The catalogue's own, when the pack carries them: looked up once
     * by the catalogue tool, they need no network and never keep a rider waiting. Otherwise the
     * host's search, from the cache when this experience's road was searched at this radius before.
     * Null when the host could not search or found nothing at all (see [buildChecked]): such an
     * answer is never cached.
     */
    private fun findSights(experience: Experience, identity: ExperienceIdentity, base: BaseRoute): FoundSights? {
        if (experience.sights.isNotEmpty()) return FoundSights(experience.sights, true)
        val key = SightsKey(identity, radiusM)
        synchronized(sightsCache) { sightsCache[key] }?.let { return it }
        // What an earlier run of the app found: the map servers are not asked again for it.
        diskCache?.read(identity.id, identity.signature, radiusM)?.let { kept ->
            val found = FoundSights(kept, true)
            synchronized(sightsCache) { sightsCache[key] = found }
            return found
        }
        // The servers did not answer a moment ago: the plain road now, the search again later.
        if (isBreakerOpen()) return null
        val thin = thinIndices(base.latitudes.size, MAX_SEARCH_POINTS)
        val lats = DoubleArray(thin.size) { base.latitudes[thin[it]] }
        val lons = DoubleArray(thin.size) { base.longitudes[thin[it]] }
        val started = clock()
        val result = try {
            sights.along(lats, lons, radiusM, ModuleSightKind.ALL)
        } catch (e: Exception) {
            openBreaker()
            return null
        }
        val late = clock() - started > SEARCH_GUARD_MILLIS
        if (!result.ok) {
            openBreaker()
            return null
        }
        val n = minOf(
            result.names.size, result.latitudes.size, result.longitudes.size,
            result.kinds.size,
        )
        if (n == 0) {
            openBreaker()
            return null
        }
        val list = ArrayList<Sight>(n)
        for (i in 0 until n) {
            val elevation = if (i < result.elevations.size) result.elevations[i] else Double.NaN
            list += Sight(result.names[i], result.latitudes[i], result.longitudes[i], result.kinds[i], elevation)
        }
        val found = FoundSights(list, result.complete)
        // A partial search is used but not remembered: the next build asks again for the rest.
        if (found.complete) {
            synchronized(sightsCache) { sightsCache[key] = found }
            diskCache?.write(identity.id, identity.signature, radiusM, list)
        }
        if (late) {
            // The host answered, but far too late to make a rider wait: this build gives the plain
            // road, and the answer is kept (above) for the next one.
            openBreaker()
            return null
        }
        resetBreaker()
        return found
    }

    /** The highest point of the route, from at most [MAX_ELEVATION_SAMPLES] samples; null if unknown. */
    private fun maxElevation(lats: DoubleArray, lons: DoubleArray): Int? {
        val pick = thinIndices(lats.size, MAX_ELEVATION_SAMPLES)
        val values = try {
            routing.elevations(
                DoubleArray(pick.size) { lats[pick[it]] },
                DoubleArray(pick.size) { lons[pick[it]] },
            )
        } catch (e: Exception) {
            null
        } ?: return null
        val top = values.filter { !it.isNaN() }.maxOrNull() ?: return null
        return top.roundToInt()
    }

    // --- stops --------------------------------------------------------------------------------

    private fun baseStops(experience: Experience): List<Stop> =
        listOf(experience.from).plus(experience.via).plus(experience.to)
            .map { Stop(it.latitude, it.longitude, it.name) }

    private class Merged(val stops: List<Stop>, val detours: List<Sight>)

    /**
     * A, then the via points and the detours as the road meets them, then B. The via points are
     * passed in order, each looked for from the segment of the one before, so a ride that comes back
     * near where it began is not read backwards. A detour is placed in the section of the road between
     * two consecutive via points (or A and B) it lies nearest to, and only there: it can never sort
     * before the via point it follows nor after the one that follows it.
     */
    private fun mergeStops(experience: Experience, base: BaseRoute, detours: List<Sight>): Merged {
        val line = RoadLine(base.polyline())
        val lastSegment = (base.latitudes.size - 2).coerceAtLeast(0)

        // Where each via point sits on the road, in order; the sections run between these segments.
        val viaSegments = ArrayList<Int>()
        var from = 0
        for (v in experience.via) {
            val p = line.project(v.latitude, v.longitude, from)
            from = p.segment
            viaSegments += p.segment
        }
        // Section k is the stretch before via point k (the last one runs on to B).
        val bounds = ArrayList<Int>()
        bounds += 0
        bounds += viaSegments
        bounds += lastSegment

        class Placed(val section: Int, val alongM: Double, val sight: Sight)

        val placed = detours.map { d ->
            var best: RoadProjection? = null
            var bestSection = 0
            for (k in 0..viaSegments.size) {
                val p = line.project(d.latitude, d.longitude, bounds[k], bounds[k + 1])
                if (best == null || p.offM < best.offM) {
                    best = p
                    bestSection = k
                }
            }
            Placed(bestSection, best?.alongM ?: 0.0, d)
        }

        val stops = ArrayList<Stop>()
        val order = ArrayList<Sight>()
        val a = experience.from
        val b = experience.to
        stops += Stop(a.latitude, a.longitude, a.name)
        for (k in 0..viaSegments.size) {
            for (d in placed.filter { it.section == k }.sortedBy { it.alongM }) {
                stops += Stop(d.sight.latitude, d.sight.longitude, d.sight.name)
                order += d.sight
            }
            if (k < experience.via.size) {
                val v = experience.via[k]
                stops += Stop(v.latitude, v.longitude, v.name)
            }
        }
        stops += Stop(b.latitude, b.longitude, b.name)
        return Merged(stops, order)
    }

    private fun plain(
        base: BaseRoute,
        stops: List<Stop>,
        complete: Boolean,
        note: String?,
    ): ExperienceRoute {
        if (!base.elevationAsked) {
            base.maxElevationM = maxElevation(base.latitudes, base.longitudes)
            base.elevationAsked = base.maxElevationM != null
        }
        return ExperienceRoute(stops, emptyList(), base.km, base.minutes, base.maxElevationM, complete, note, false)
    }

    private fun failed(experience: Experience, note: String): ExperienceRoute = ExperienceRoute(
        stops = baseStops(experience),
        detours = emptyList(),
        km = experience.km,
        minutes = experience.minutes,
        maxElevationM = experience.maxElevationM,
        complete = false,
        note = note,
        error = true,
    )

    private fun messageOf(kind: Int): String = when (kind) {
        ModuleRouteError.NO_NETWORK -> RouteNotes.NO_NETWORK
        ModuleRouteError.RATE_LIMITED -> RouteNotes.BUSY
        ModuleRouteError.NO_API_KEY, ModuleRouteError.TOO_LONG -> CoreStrings.kindMessage(kind)
        else -> RouteNotes.OTHER
    }

    // --- caches -------------------------------------------------------------------------------

    /** An experience as the caches know it: its id and a hash of the places it is made of. */
    private data class ExperienceIdentity(val id: String, val signature: Int)

    private fun identityOf(e: Experience) = ExperienceIdentity(e.id, listOf(e.from, e.to, e.via).hashCode())

    private data class SightsKey(val experience: ExperienceIdentity, val radiusM: Int)

    private data class ResultKey(
        val experience: ExperienceIdentity,
        val detours: Int,
        val culture: Int?,
        val maxKm: Int,
        val maxMinutes: Int,
        val alt: Int,
    )

    private fun cachedResult(key: ResultKey): ExperienceRoute? = synchronized(resultCache) { resultCache[key] }

    private fun store(key: ResultKey, route: ExperienceRoute) {
        synchronized(resultCache) { resultCache[key] = route }
    }

    private fun cachedBase(identity: ExperienceIdentity): BaseRoute? = synchronized(baseCache) { baseCache[identity] }

    companion object {
        /** The final route is tried at most this many extra times, each without one more detour. */
        const val MAX_RETRIES = 2

        /** The road sent to the sights search is thinned to about this many points. */
        const val MAX_SEARCH_POINTS = 500

        const val MAX_ELEVATION_SAMPLES = 200

        /** After a failed search the host is left alone this long (the rider's retry bypasses it). */
        const val BREAKER_MILLIS = 5L * 60 * 1000

        /**
         * A host search that took longer than this is treated as unavailable for this build (the host
         * bounds itself at 30 s; this is the module's own guard against a slow answer).
         */
        const val SEARCH_GUARD_MILLIS = 40_000L

        /** Result cache size: how many finished routes are kept. */
        const val CACHE_SIZE = 40

        /** Sights cache size: how many roads' sights are kept. */
        const val SIGHTS_CACHE_SIZE = 60

        private const val FALLBACK_KPH = 50.0

        /** When the breaker closes by itself, in the clock's milliseconds; one per process. */
        private val breakerUntil = AtomicLong(Long.MIN_VALUE)

        // One per process: the app makes a new ExperienceRoutes whenever the page opens, and what was
        // computed should outlive it. Plain-road entries are few (one per experience) and small.
        private val baseCache = HashMap<ExperienceIdentity, BaseRoute>()

        /** What the sights search found per road and radius (never a failed, empty or partial search). */
        private val sightsCache = object : LinkedHashMap<SightsKey, FoundSights>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SightsKey, FoundSights>?): Boolean =
                size > SIGHTS_CACHE_SIZE
        }

        private val resultCache = object : LinkedHashMap<ResultKey, ExperienceRoute>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ResultKey, ExperienceRoute>?): Boolean =
                size > CACHE_SIZE
        }

        /** Forgets everything cached. For tests; the app has no use for it. */
        internal fun clearCaches() {
            synchronized(baseCache) { baseCache.clear() }
            synchronized(resultCache) { resultCache.clear() }
            synchronized(sightsCache) { sightsCache.clear() }
            breakerUntil.set(Long.MIN_VALUE)
        }

        /** Indices that keep the first and the last of [size] points and spread the rest evenly, at most [max]. */
        internal fun thinIndices(size: Int, max: Int): IntArray {
            if (size <= max) return IntArray(size) { it }
            return IntArray(max) { (it.toLong() * (size - 1) / (max - 1)).toInt() }
        }

        private fun polylineLengthM(lats: DoubleArray, lons: DoubleArray): Double {
            var sum = 0.0
            for (i in 0 until lats.size - 1) sum += LatLng(lats[i], lons[i]).distanceTo(LatLng(lats[i + 1], lons[i + 1]))
            return sum
        }
    }
}

/** The sentences the rider may read when something went short. */
internal object RouteNotes {
    const val NO_NETWORK = "No internet connection. Detours and routes need one."
    const val BUSY = "The routing server is busy. Wait a minute and try again."
    const val OTHER = "This route could not be planned right now."
    const val UNEXPECTED = "This route could not be planned right now."
    const val SIGHTS_UNAVAILABLE = "Places worth a detour could not be looked up, so this is the plain route."
    const val PARTIAL_SEARCH = "Only part of the road was searched for detours."
    const val NO_DETOURS = "Nothing worth a detour was found near this road within your limits."
    const val BASE_OVER_LIMIT = "This route is already longer than your limits, so no detours were added."
    const val DETOURS_TOO_LONG = "The detours would not fit your distance and time limits, so this is the plain route."
    const val DETOURS_FAILED = "The route with detours could not be planned, so this is the plain route."
}
