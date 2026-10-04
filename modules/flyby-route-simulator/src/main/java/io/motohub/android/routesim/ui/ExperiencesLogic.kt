// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The Experiences page's arithmetic, kept out of the composables so it can be tested without a phone:
// how a card reads, which cards still need their route and in what order, what is offline.
package io.motohub.android.routesim.ui

import io.motohub.android.routesim.core.Stop
import io.motohub.android.routesim.core.routePreferenceOf
import io.motohub.android.routesim.experiences.CountryLocator
import io.motohub.android.routesim.experiences.DetourPlanner
import io.motohub.android.routesim.experiences.Experience
import io.motohub.android.routesim.experiences.ExperienceFilters
import io.motohub.android.routesim.experiences.ExperienceRoute
import io.motohub.android.routesim.experiences.Mismatch
import io.motohub.android.routesim.experiences.RankedExperience
import io.motohub.android.routesim.experiences.RouteNotes
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything of the filters that changes the route an experience gets, and which alternative of it. */
internal data class RouteKey(val detours: Int, val culture: Int?, val maxKm: Int, val maxMinutes: Int, val alt: Int)

/**
 * One route to compute for a card. Two requests are the same when they ask for the same experience
 * and the same [key]: the other filters do not change the route.
 */
internal class RouteRequest(val experience: Experience, val filters: ExperienceFilters, val alt: Int) {
    val id: String get() = experience.id
    val key: RouteKey = ExperiencesLogic.routeKey(filters, alt)

    override fun equals(other: Any?): Boolean = other is RouteRequest && other.id == id && other.key == key
    override fun hashCode(): Int = id.hashCode() * 31 + key.hashCode()
}

/** A route that came back for a card, and the [key] it was computed for. */
internal class CardResult(val key: RouteKey, val route: ExperienceRoute)

/** What a card shows, already in words. */
internal data class CardView(
    val id: String,
    val name: String,
    val region: String,
    val kmText: String,
    val durationText: String,
    /** Null when the highest point is not known. */
    val elevationText: String?,
    val tags: List<String>,
    /** One muted line when the card misses a filter, otherwise null. */
    val mismatchLine: String?,
    val shape: List<Pair<Double, Double>>,
    /** The route is being computed (or waits its turn). */
    val loading: Boolean,
    /** The numbers are the router's, not the catalogue's typical ones. */
    val real: Boolean,
    /** "Idea 2" once the rider asked for another set of detours, otherwise null. */
    val ideaText: String?,
    val canOtherIdeas: Boolean,
    /** The detours could not be looked up (the map service is not answering): the plain route is all there is. */
    val detoursUnavailable: Boolean = false,
)

/** What opening a card puts in the planner. */
internal class OpenPlan(val title: String, val stops: List<Stop>, val note: String?)

/** One of the four "target" sliders of the filter panel. */
internal enum class TargetAxis {
    NATURE, TWISTY, GRAVEL, CULTURE;

    fun read(f: ExperienceFilters): Int? = when (this) {
        NATURE -> f.nature
        TWISTY -> f.twisty
        GRAVEL -> f.gravel
        CULTURE -> f.culture
    }

    fun write(f: ExperienceFilters, value: Int?): ExperienceFilters {
        val v = value?.coerceIn(0, 100)
        return when (this) {
            NATURE -> f.copy(nature = v)
            TWISTY -> f.copy(twisty = v)
            GRAVEL -> f.copy(gravel = v)
            CULTURE -> f.copy(culture = v)
        }
    }

    /** "Any" is switched on by clearing the value, and off by starting from the middle. */
    fun toggleAny(f: ExperienceFilters): ExperienceFilters = write(f, if (read(f) == null) MIDDLE else null)

    /** What the slider's 0 end and 100 end are called. */
    fun lowLabel(): String = when (this) {
        NATURE -> Strings.AXIS_CITY
        TWISTY -> Strings.AXIS_FAST
        GRAVEL -> Strings.AXIS_TARMAC
        CULTURE -> Strings.AXIS_SCENERY
    }

    fun highLabel(): String = when (this) {
        NATURE -> Strings.AXIS_NATURE
        TWISTY -> Strings.AXIS_BENDS
        GRAVEL -> Strings.AXIS_GRAVEL
        CULTURE -> Strings.AXIS_CULTURE
    }

    companion object {
        const val MIDDLE = 50
    }
}

/** Why the routes are about to be asked for again, which decides how long to wait first. */
internal enum class PlanReason { FILTERS, VISIBILITY, OTHER_IDEAS, OPEN }

internal object ExperiencesLogic {

    /** How many cards the page shows. */
    const val CARD_LIMIT = 10

    /** Cards below the last visible one whose route is also asked for. */
    const val LOOK_AHEAD = 1

    const val ROUTE_DEBOUNCE_MS = 500L
    const val VISIBILITY_DEBOUNCE_MS = 150L
    const val SAVE_DEBOUNCE_MS = 400L

    /** How long a tapped card waits for its route before the planner opens with the catalogue's own stops. */
    const val OPEN_DEADLINE_MS = 15_000L

    const val KM_STEP = 10
    const val MINUTES_STEP = 15

    // ---- country

    /**
     * The country the page opens on: where the rider last was, when that is in the catalogue; the one
     * chosen last; Italy; and when the catalogue has none of those, its first country. Null only for
     * an empty catalogue. [position] is [latitude, longitude] as the host gives it.
     */
    fun initialCountry(position: DoubleArray?, available: List<String>, last: String?): String? {
        val codes = available.map { it.trim().uppercase() }
        if (codes.isEmpty()) return null
        if (position != null && position.size >= 2) {
            val here = CountryLocator.locate(position[0], position[1])
            if (here != null && here in codes) return here
        }
        val chosen = last?.trim()?.uppercase()
        if (chosen != null && chosen in codes) return chosen
        return if ("IT" in codes) "IT" else codes.first()
    }

    // ---- numbers as the card prints them

    fun kmText(km: Double): String = Strings.distanceKm(km.roundToInt())

    fun durationText(minutes: Double): String = Fmt.duration(minutes * 60.0)

    /** The highest point, or null when it is not known. */
    fun elevationText(meters: Int?): String? = if (meters == null || meters <= 0) null else Strings.elevationMax(meters)

    // ---- what a card misses

    /** One mismatch in words. [filters] and [experience] say in which direction a slider is missed. */
    fun mismatchLabel(m: Mismatch, filters: ExperienceFilters, experience: Experience): String = when (m) {
        is Mismatch.Km -> if (m.km > 0) Strings.kmLonger(m.km) else Strings.kmShorter(-m.km)
        is Mismatch.Minutes ->
            if (m.minutes > 0) Strings.timeLonger(Fmt.duration(m.minutes * 60.0))
            else Strings.timeShorter(Fmt.duration(-m.minutes * 60.0))
        Mismatch.Nature ->
            if ((filters.nature ?: 0) > experience.nature) Strings.MISMATCH_LITTLE_NATURE else Strings.MISMATCH_LITTLE_CITY
        Mismatch.Twisty ->
            if ((filters.twisty ?: 0) > experience.twisty) Strings.MISMATCH_FEW_BENDS else Strings.MISMATCH_MANY_BENDS
        Mismatch.Gravel ->
            if ((filters.gravel ?: 0) > experience.gravel) Strings.MISMATCH_LITTLE_GRAVEL else Strings.MISMATCH_MUCH_GRAVEL
        Mismatch.Region -> Strings.notInRegion(filters.region.orEmpty())
    }

    /** The first mismatch, and how many more there are; null when the card misses nothing. */
    fun mismatchLine(list: List<Mismatch>, filters: ExperienceFilters, experience: Experience): String? {
        if (list.isEmpty()) return null
        val first = mismatchLabel(list[0], filters, experience)
        return if (list.size == 1) first else first + " · " + Strings.moreMismatches(list.size - 1)
    }

    /**
     * The mismatches of a card once the router has said how long its route really is: the km and time
     * ones are worked out again from the real numbers (detours change them), the others stay. A route
     * that failed says nothing new, so the catalogue's own verdict stands.
     */
    fun mismatchesFor(ranked: RankedExperience, filters: ExperienceFilters, real: ExperienceRoute?): List<Mismatch> {
        if (real == null || real.error) return ranked.mismatches
        val others = ranked.mismatches.filter { it !is Mismatch.Km && it !is Mismatch.Minutes }
        val km = excess(real.km, filters.minKm.toDouble(), filters.maxKm.toDouble()).roundToInt()
        val minutes = excess(real.minutes, filters.minMinutes.toDouble(), filters.maxMinutes.toDouble()).roundToInt()
        val out = ArrayList<Mismatch>()
        if (km != 0) out += Mismatch.Km(km)
        if (minutes != 0) out += Mismatch.Minutes(minutes)
        out += others
        return out
    }

    /** How far [v] lies beyond [lo]..[hi]: positive above, negative below, 0 inside; as the ranker counts it. */
    private fun excess(v: Double, lo: Double, hi: Double): Double = when {
        v > hi -> v - hi
        v < lo -> v - lo
        else -> 0.0
    }

    // ---- cards

    fun routeKey(filters: ExperienceFilters, alt: Int): RouteKey = RouteKey(
        detours = filters.detours.coerceIn(0, ExperienceFilters.MAX_DETOURS),
        culture = filters.culture,
        maxKm = filters.maxKm,
        maxMinutes = filters.maxMinutes,
        alt = alt.coerceIn(0, DetourPlanner.MAX_ALT),
    )

    /** The route's numbers are the router's, unless it failed (then they are the catalogue's, and not shown as real). */
    fun isReal(result: CardResult?, key: RouteKey): Boolean = result != null && result.key == key && !result.route.error

    fun cardView(
        ranked: RankedExperience,
        filters: ExperienceFilters,
        alt: Int,
        result: CardResult?,
        busy: Boolean,
    ): CardView {
        val e = ranked.experience
        val key = routeKey(filters, alt)
        val current = result?.takeIf { it.key == key }
        val real = current != null && !current.route.error
        val route = if (real) current?.route else null
        val km = route?.km ?: e.km
        val minutes = route?.minutes ?: e.minutes
        val elevation = if (route != null) route.maxElevationM ?: e.maxElevationM else e.maxElevationM
        return CardView(
            id = e.id,
            name = e.name,
            region = e.region,
            kmText = kmText(km),
            durationText = durationText(minutes),
            elevationText = elevationText(elevation),
            tags = ranked.tags.take(MAX_TAGS),
            mismatchLine = mismatchLine(mismatchesFor(ranked, filters, route), filters, e),
            shape = e.shape,
            loading = busy && current == null,
            real = real,
            ideaText = ideaText(alt),
            canOtherIdeas = canOtherIdeas(filters),
            detoursUnavailable = !busy && detoursUnavailable(current),
        )
    }

    /**
     * The card's answer for the current filters is the plain road because the places worth a detour
     * could not be looked up (the circuit breaker is open, or the search failed): it says so instead of
     * promising detours that are not coming.
     */
    fun detoursUnavailable(current: CardResult?): Boolean = current != null && current.route.note == RouteNotes.SIGHTS_UNAVAILABLE

    private const val MAX_TAGS = 4

    /** "Idea 2" for the first alternative, nothing for the best set. */
    fun ideaText(alt: Int): String? = if (alt <= 0) null else Strings.ideaNumber(alt + 1)

    /** The next alternative; it stops where the planner stops telling alternatives apart. */
    fun nextAlt(alt: Int): Int = (alt.coerceAtLeast(0) + 1).coerceAtMost(DetourPlanner.MAX_ALT)

    /** Other detours only exist when some are asked for. */
    fun canOtherIdeas(filters: ExperienceFilters): Boolean = filters.detours > 0

    // ---- which routes to compute

    /**
     * The routes still to ask for, nearest the top of the list first: the cards on screen
     * ([visibleIds]) and [lookAhead] below them, minus those that already have the route for the
     * current filters (a failed one counts: it is not asked again until the rider changes something).
     */
    fun wanted(
        ranked: List<RankedExperience>,
        alts: Map<String, Int>,
        filters: ExperienceFilters,
        results: Map<String, CardResult>,
        visibleIds: Set<String>,
        lookAhead: Int = LOOK_AHEAD,
    ): List<RouteRequest> {
        var first = Int.MAX_VALUE
        var last = -1
        for (i in ranked.indices) {
            if (ranked[i].experience.id in visibleIds) {
                first = min(first, i)
                last = i
            }
        }
        if (last < 0) return emptyList()
        val end = min(ranked.lastIndex, last + lookAhead.coerceAtLeast(0))
        val out = ArrayList<RouteRequest>()
        for (i in first..end) {
            val e = ranked[i].experience
            val alt = alts[e.id] ?: 0
            if (results[e.id]?.key == routeKey(filters, alt)) continue
            out += RouteRequest(e, filters, alt)
        }
        return out
    }

    /** How long to wait before the routes are asked for again, so a slider being dragged asks only once. */
    fun debounceMs(reason: PlanReason): Long = when (reason) {
        PlanReason.FILTERS -> ROUTE_DEBOUNCE_MS
        PlanReason.VISIBILITY -> VISIBILITY_DEBOUNCE_MS
        PlanReason.OTHER_IDEAS, PlanReason.OPEN -> 0L
    }

    /**
     * When the next refresh of the routes is due, given one already due at [pending] (null when none
     * is) and a wait of [waitMs] from [now]. A pending wait is never replaced by a shorter one: a
     * card that scrolls into view (150 ms) must not cut short the pause after a slider moved (500 ms),
     * or routes would be asked for at a value the slider only passed through. A result at or before
     * [now] means "refresh at once"; a wait of 0 with something pending leaves the pending one alone.
     */
    fun dueAt(pending: Long?, now: Long, waitMs: Long): Long = maxOf(pending ?: Long.MIN_VALUE, now + waitMs.coerceAtLeast(0L))

    // ---- offline

    /** The router said there is no connection. */
    fun isOffline(route: ExperienceRoute): Boolean = route.note == RouteNotes.NO_NETWORK

    /**
     * A route that is worth asking for again: it failed outright, or it ended as the plain road for a
     * reason that is not permanent (the places could not be looked up, the server was busy, something
     * else went wrong). The other notes are verdicts about the road and the limits, and stay.
     */
    fun isRetryable(route: ExperienceRoute): Boolean = route.error || when (route.note) {
        RouteNotes.NO_NETWORK, RouteNotes.SIGHTS_UNAVAILABLE, RouteNotes.BUSY, RouteNotes.OTHER,
        RouteNotes.UNEXPECTED, RouteNotes.DETOURS_FAILED -> true
        else -> false
    }

    /** What the page's one banner says. */
    enum class Banner { NONE, OFFLINE, RETRY }

    /**
     * The banner for the page: offline when some shown card failed for want of a connection, otherwise
     * "some detours could not be found" when some shown card came back worse than it could have.
     */
    fun banner(ranked: List<RankedExperience>, alts: Map<String, Int>, filters: ExperienceFilters, results: Map<String, CardResult>): Banner {
        var retry = false
        for (r in ranked) {
            val result = results[r.experience.id] ?: continue
            if (result.key != routeKey(filters, alts[r.experience.id] ?: 0)) continue
            if (isOffline(result.route)) return Banner.OFFLINE
            if (isRetryable(result.route)) retry = true
        }
        return if (retry) Banner.RETRY else Banner.NONE
    }

    /** One banner for the page: some card on it failed for want of a connection. */
    fun offline(ranked: List<RankedExperience>, alts: Map<String, Int>, filters: ExperienceFilters, results: Map<String, CardResult>): Boolean =
        banner(ranked, alts, filters, results) == Banner.OFFLINE

    /** The results without the ones worth asking for again, so those cards are asked for again. */
    fun withoutFailures(results: Map<String, CardResult>): Map<String, CardResult> =
        results.filterValues { !isRetryable(it.route) }

    // ---- an answer that arrives

    /** An answer computed for [key] still stands for a card whose filters and idea now give [current]. */
    fun answerIsCurrent(key: RouteKey, filters: ExperienceFilters, alt: Int): Boolean = key == routeKey(filters, alt)

    // ---- Plan by hand

    /**
     * Whether "Plan by hand" leaves the planner as it is: when it holds stops that were not made from
     * an experience (a hand-built plan, or a saved plan made by hand). The stops of an experience, and
     * an empty planner, start blank.
     */
    fun byHandKeepsPlanner(hasStops: Boolean, titleOverride: String?): Boolean = hasStops && titleOverride == null

    // ---- how the planner routes

    /** What decides that a planned route can be reused: the stops and the preference it was planned with. */
    fun preparedKeyFor(stopsKey: String, scenic: Boolean): String = stopsKey + "|" + routePreferenceOf(scenic)

    // ---- opening a card

    /** A, the via points, B: what the planner gets when no route could be had. */
    fun catalogueStops(e: Experience): List<Stop> =
        (listOf(e.from) + e.via + e.to).map { Stop(it.latitude, it.longitude, it.name) }

    /** What to put in the planner for [e]: the route's stops when it has them, the catalogue's own otherwise. */
    fun planToOpen(e: Experience, route: ExperienceRoute?): OpenPlan {
        val stops = if (route == null || route.error || route.stops.size < 2) catalogueStops(e) else route.stops
        return OpenPlan(e.name, stops, route?.note?.takeIf { it.isNotBlank() })
    }

    /** How much of the opening deadline is left [nowMs], for a tap at [startedMs] (both on one monotonic clock); 0 once it has passed. */
    fun openRemainingMs(startedMs: Long, nowMs: Long): Long =
        (OPEN_DEADLINE_MS - (nowMs - startedMs)).coerceIn(0L, OPEN_DEADLINE_MS)

    /** The tap at [startedMs] has waited long enough: open the planner without the detours. */
    fun openTimedOut(startedMs: Long, nowMs: Long): Boolean = openRemainingMs(startedMs, nowMs) <= 0L

    /** What the planner gets when the route did not come in time: the catalogue's stops, and why they are plain. */
    fun slowPlan(e: Experience): OpenPlan = OpenPlan(e.name, catalogueStops(e), Strings.DETOURS_SLOW_NOTICE)

    /**
     * The card's mark after a tap that gave up waiting: a failed route that says the detours could not
     * be looked up, so the card reads "Detours unavailable" and the page offers "Try again". A build
     * still running for the card replaces it when it ends.
     */
    fun slowRoute(e: Experience): ExperienceRoute =
        ExperienceRoute(catalogueStops(e), emptyList(), e.km, e.minutes, e.maxElevationM, false, RouteNotes.SIGHTS_UNAVAILABLE, true)

    /** The result [route] stands for when the rider tapped before the card had one: a failed route of its own. */
    fun failedRoute(e: Experience): ExperienceRoute =
        ExperienceRoute(catalogueStops(e), emptyList(), e.km, e.minutes, e.maxElevationM, false, null, true)

    // ---- filters

    /** How many of the filters are set to something other than their default. */
    fun activeCount(f: ExperienceFilters): Int {
        val d = ExperienceFilters.DEFAULT
        var n = 0
        if (f.minKm != d.minKm || f.maxKm != d.maxKm) n++
        if (f.minMinutes != d.minMinutes || f.maxMinutes != d.maxMinutes) n++
        if (f.nature != null) n++
        if (f.twisty != null) n++
        if (f.gravel != null) n++
        if (f.culture != null) n++
        if (f.region != null) n++
        if (f.detours != d.detours) n++
        return n
    }

    fun isDefault(f: ExperienceFilters): Boolean = activeCount(f) == 0

    /** "Any distance", "Up to 200 km", "100 km or more", "100 km – 200 km". */
    fun kmRangeText(f: ExperienceFilters): String = rangeText(
        f.minKm, f.maxKm, ExperienceFilters.DEFAULT_MAX_KM, Strings.ANY_DISTANCE,
    ) { Strings.distanceKm(it) }

    /** The same for the time: "Any time", "Up to 3h 00m", "1h 00m or more", "1h 00m – 3h 00m". */
    fun minutesRangeText(f: ExperienceFilters): String = rangeText(
        f.minMinutes, f.maxMinutes, ExperienceFilters.DEFAULT_MAX_MINUTES, Strings.ANY_TIME,
    ) { Fmt.duration(it * 60.0) }

    private fun rangeText(low: Int, high: Int, top: Int, any: String, format: (Int) -> String): String {
        val noLow = low <= 0
        val noHigh = high >= top
        return when {
            noLow && noHigh -> any
            noLow -> Strings.rangeUpTo(format(high))
            noHigh -> Strings.rangeFrom(format(low))
            else -> Strings.rangeBetween(format(low), format(high))
        }
    }

    /** The region selector's choices: null is "All", then the pack's regions. */
    fun regionOptions(regions: List<String>): List<String?> = listOf<String?>(null) + regions

    /** The region [delta] places along the choices from [current]; it stops at the ends. */
    fun stepRegion(options: List<String?>, current: String?, delta: Int): String? {
        if (options.isEmpty()) return null
        val at = options.indexOfFirst { it.equals(current, ignoreCase = true) }.let { if (it < 0) 0 else it }
        return options[(at + delta).coerceIn(0, options.lastIndex)]
    }

    fun regionText(region: String?): String = region ?: Strings.REGION_ALL

    fun detoursText(count: Int): String = if (count <= 0) Strings.DETOURS_NONE else count.toString()
}

/** The geometry of the two slider widgets, without a screen. */
internal object SliderMath {

    /** The value (a multiple of [step] from [min]) a touch at fraction [fraction] of the track stands for. */
    fun valueOf(fraction: Float, min: Int, max: Int, step: Int): Int {
        val s = step.coerceAtLeast(1)
        val raw = min + fraction.coerceIn(0f, 1f) * (max - min)
        val snapped = min + (Math.round((raw - min) / s) * s)
        return snapped.coerceIn(min, max)
    }

    fun fractionOf(value: Int, min: Int, max: Int): Float =
        if (max <= min) 0f else ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)

    /**
     * Which thumb a touch at [fraction] takes: 0 the low one, 1 the high one. The nearer one; when
     * both are at the same place, the low one for a touch to the left and the high one otherwise.
     */
    fun nearestThumb(fraction: Float, lowFraction: Float, highFraction: Float): Int {
        val dLow = abs(fraction - lowFraction)
        val dHigh = abs(fraction - highFraction)
        return when {
            dLow < dHigh -> 0
            dHigh < dLow -> 1
            fraction < lowFraction -> 0
            else -> 1
        }
    }

    /** The range with [thumb] moved to [value]; a thumb cannot pass the other one. */
    fun moveThumb(thumb: Int, value: Int, low: Int, high: Int): Pair<Int, Int> =
        if (thumb == 0) minOf(value, high) to high else low to maxOf(value, low)
}

/** Where the points of a card's silhouette go in its small picture. */
internal object ShapeMath {

    /**
     * [shape] (latitude, longitude pairs) as x, y in a box [width] by [height], [pad] in from the
     * edges, at one scale on both axes so the ride is not stretched. Longitudes are shortened by the
     * cosine of the mid latitude, as the ground is. North is up; the picture is centred.
     */
    fun project(shape: List<Pair<Double, Double>>, width: Float, height: Float, pad: Float): List<Pair<Float, Float>> {
        if (shape.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for ((lat, lon) in shape) {
            minLat = min(minLat, lat); maxLat = maxOf(maxLat, lat)
            minLon = min(minLon, lon); maxLon = maxOf(maxLon, lon)
        }
        val k = cos(Math.toRadians((minLat + maxLat) / 2.0)).coerceAtLeast(0.01)
        val spanX = (maxLon - minLon) * k
        val spanY = maxLat - minLat
        val availW = (width - 2 * pad).coerceAtLeast(1f).toDouble()
        val availH = (height - 2 * pad).coerceAtLeast(1f).toDouble()
        val scale = when {
            spanX <= 0.0 && spanY <= 0.0 -> 0.0
            spanX <= 0.0 -> availH / spanY
            spanY <= 0.0 -> availW / spanX
            else -> min(availW / spanX, availH / spanY)
        }
        val offsetX = pad + (availW - spanX * scale) / 2.0
        val offsetY = pad + (availH - spanY * scale) / 2.0
        return shape.map { (lat, lon) ->
            ((offsetX + (lon - minLon) * k * scale).toFloat()) to ((offsetY + (maxLat - lat) * scale).toFloat())
        }
    }
}

/**
 * The routes to compute, one after another. The page puts the wish list in with [replace] (the cards
 * on screen, nearest the top first) and a tapped card in front of it with [pin]; one worker takes
 * them with [next] and never more than one at a time, because the host paces the routing server
 * and a burst would only be refused.
 *
 * [replace] and [pin] return true when the caller has to start the worker (there was none running).
 * Every method is safe to call from any thread.
 */
internal class RouteQueue {

    private val lock = Any()
    private val pending = ArrayList<RouteRequest>()
    private var wished: List<RouteRequest> = emptyList()
    private var pinned: RouteRequest? = null
    private var inFlight: RouteRequest? = null
    private var running = false

    /** The wish list becomes [wanted]: what was waiting and is no longer wanted is dropped; what runs is left alone. */
    fun replace(wanted: List<RouteRequest>): Boolean = synchronized(lock) {
        wished = wanted
        rebuild()
        startNeeded()
    }

    /** [request] goes first, and stays wanted whatever the wish list becomes, until [unpin] or it is done. */
    fun pin(request: RouteRequest): Boolean = synchronized(lock) {
        pinned = request
        rebuild()
        startNeeded()
    }

    fun unpin() = synchronized(lock) {
        pinned = null
        rebuild()
    }

    /** Forgets everything waiting; the one running cannot be stopped and finishes. */
    fun clear() = synchronized(lock) {
        wished = emptyList()
        pinned = null
        pending.clear()
    }

    /** The next to compute, or null when none is waiting (and then the worker is no longer running). */
    fun next(): RouteRequest? = synchronized(lock) {
        if (pending.isEmpty()) {
            running = false
            inFlight = null
            return null
        }
        pending.removeAt(0).also { inFlight = it }
    }

    /** [request] is done: it is not wanted any more. */
    fun finished(request: RouteRequest) = synchronized(lock) {
        if (inFlight == request) inFlight = null
        wished = wished.filter { it != request }
        if (pinned == request) pinned = null
        pending.remove(request)
    }

    /** The experiences with a route waiting or being computed. */
    fun busyIds(): Set<String> = synchronized(lock) {
        val ids = LinkedHashSet<String>()
        inFlight?.let { ids += it.id }
        pending.forEach { ids += it.id }
        ids
    }

    /**
     * The worker's loop: takes requests until none is left, computes each with [compute] and hands
     * the answer to [deliver] before it counts as finished. [onChange] is told whenever what is busy
     * changed. A [compute] that throws is answered with a failed route of the catalogue's numbers.
     */
    fun drain(
        shouldContinue: () -> Boolean,
        compute: (RouteRequest) -> ExperienceRoute,
        deliver: (RouteRequest, ExperienceRoute) -> Unit,
        onChange: () -> Unit,
    ) {
        while (true) {
            if (!shouldContinue()) {
                synchronized(lock) { running = false }
                return
            }
            val request = next() ?: return
            onChange()
            val route = try {
                compute(request)
            } catch (e: Exception) {
                ExperiencesLogic.failedRoute(request.experience)
            }
            deliver(request, route)
            finished(request)
            onChange()
        }
    }

    private fun rebuild() {
        pending.clear()
        val first = pinned
        if (first != null && first != inFlight) pending += first
        for (r in wished) if (r != inFlight && r !in pending) pending += r
    }

    private fun startNeeded(): Boolean {
        if (running || pending.isEmpty()) return false
        running = true
        return true
    }
}
