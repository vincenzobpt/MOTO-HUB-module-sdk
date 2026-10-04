// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Chooses which sights near a road are worth a detour. Pure: no host, no network, no randomness.
package io.motohub.android.routesim.experiences

import io.motohub.android.module.ModuleSightKind
import io.motohub.android.routesim.sim.LatLng
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A named place found near a road. [kind] is a [ModuleSightKind]; [elevationM] is NaN when unknown. */
data class Sight(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val kind: Int,
    val elevationM: Double = Double.NaN,
)

/**
 * Picks the detours of an experience.
 *
 * A detour is a passage: a waypoint the route goes through on its way from A to B, never an
 * out-and-back. So a sight costs about twice its distance from the road, bent by [BEND_FACTOR]
 * (the road that leaves the main one and the one that comes back are rarely the same length), and
 * the picks are returned in the order the road meets them.
 *
 * The choice is greedy over a ranking: each sight is worth its kind (from the culture slider), a
 * little more when it is close to the road, and a little more still when it is a high pass. A sight
 * is taken unless it is too near the start or the end, a near-duplicate of one already taken, too
 * close along the road to one already taken, or too dear for what is left of the km and time budget.
 *
 * The alternatives ([alt]) are a chain: variant 0 is the greedy choice over every sight; variant n
 * is the greedy choice again after banning, for good, the best-ranked pick of variants 0..n-1 (one
 * per variant). A banned sight is in one of the earlier variants and absent from this one, so the
 * n-th variant differs from all the earlier ones as long as it is not empty. When there are not
 * enough sights left for a new set the last non-empty variant is returned again.
 */
object DetourPlanner {

    /** How far from the road a sight may be, by default. */
    const val DEFAULT_RADIUS_M = 4_000

    /** The extra distance of a passage is this times twice the sight's distance from the road. */
    const val BEND_FACTOR = 1.4

    /** Speed assumed on the extra distance, to estimate the added time. */
    const val DETOUR_KPH = 45.0

    /** Sights nearer than this (straight line) to the start or the end are not detours. */
    const val MIN_FROM_ENDS_M = 1_000.0

    /** Two picks are never nearer than this to each other, nor to the start or the end. */
    const val MIN_APART_M = 400.0

    /** Two picks are never nearer than this share of the road's length to each other, along it. */
    const val MIN_SPACING_FRACTION = 0.08

    /** The highest [alt] told apart; beyond it the last variant is returned. */
    const val MAX_ALT = 60

    /**
     * The sights worth a detour on [road] (latitude, longitude pairs, start to end), in the order the
     * road meets them. At most [ExperienceFilters.detours] of them, and none when the base route
     * ([baseKm], [baseMinutes]) is already over [ExperienceFilters.maxKm] or
     * [ExperienceFilters.maxMinutes]. [alt] is 0 for the best set, n for the n-th alternative.
     * [avoid] are places the ride already goes through (its start, via points and end): a sight
     * within [MIN_FROM_ENDS_M] of one, or with the same name as one, is that stop and not a detour.
     */
    fun plan(
        road: List<Pair<Double, Double>>,
        sights: List<Sight>,
        filters: ExperienceFilters,
        baseKm: Double,
        baseMinutes: Double,
        alt: Int,
        radiusM: Int = DEFAULT_RADIUS_M,
        avoid: List<ExperiencePlace> = emptyList(),
    ): List<Sight> {
        val ranked = planRanked(road, sights, filters, baseKm, baseMinutes, alt, radiusM, avoid)
        if (ranked.size < 2) return ranked
        val line = RoadLine(road)
        return ranked.sortedBy { line.project(it.latitude, it.longitude).alongM }
    }

    /**
     * Like [plan] but in the order the picks were taken: the best first, so the last one is the first
     * to give up when the real route turns out too long.
     */
    fun planRanked(
        road: List<Pair<Double, Double>>,
        sights: List<Sight>,
        filters: ExperienceFilters,
        baseKm: Double,
        baseMinutes: Double,
        alt: Int,
        radiusM: Int = DEFAULT_RADIUS_M,
        avoid: List<ExperiencePlace> = emptyList(),
    ): List<Sight> {
        val wanted = filters.detours.coerceIn(0, ExperienceFilters.MAX_DETOURS)
        if (wanted == 0 || road.size < 2 || sights.isEmpty()) return emptyList()
        if (baseKm > filters.maxKm || baseMinutes > filters.maxMinutes) return emptyList()

        val line = RoadLine(road)
        if (line.lengthM <= 0.0) return emptyList()
        val pool = rank(line, sights, filters.culture, radiusM.coerceAtLeast(1), avoid)
        if (pool.isEmpty()) return emptyList()

        val budget = Budget(filters.maxKm - baseKm, filters.maxMinutes - baseMinutes)
        val banned = HashSet<Int>()
        var current = pick(pool, line, wanted, budget, banned)
        for (n in 1..alt.coerceIn(0, MAX_ALT)) {
            if (current.isEmpty()) break
            banned += current.minOrNull() ?: break
            val next = pick(pool, line, wanted, budget, banned)
            if (next.isEmpty()) break
            current = next
        }
        return current.map { pool[it].sight }
    }

    /** What a kind of sight is worth for the culture slider (0 scenery, 100 culture, null neutral). */
    fun kindWeight(kind: Int, culture: Int?): Double {
        val heritage = kind == ModuleSightKind.HERITAGE
        val scenery = kind == ModuleSightKind.VIEWPOINT || kind == ModuleSightKind.PASS
        if (!heritage && !scenery) return 0.0
        if (culture == null) return 1.0
        val t = culture.coerceIn(0, 100) / 100.0
        return if (heritage) 0.35 + 0.65 * t else 1.0 - 0.65 * t
    }

    /** The extra km of a passage through a sight [offRoadM] metres from the road. */
    fun extraKm(offRoadM: Double): Double = 2.0 * (offRoadM / 1000.0) * BEND_FACTOR

    /** The extra minutes for [extraKm] kilometres at [DETOUR_KPH]. */
    fun extraMinutes(extraKm: Double): Double = extraKm / DETOUR_KPH * 60.0

    private class Candidate(val sight: Sight, val alongM: Double, val offM: Double, val value: Double)

    private class Budget(val km: Double, val minutes: Double)

    /** Every usable sight, best first; ties broken by distance (to the metre), then name and position. */
    private fun rank(
        line: RoadLine,
        sights: List<Sight>,
        culture: Int?,
        radiusM: Int,
        avoid: List<ExperiencePlace>,
    ): List<Candidate> {
        val a = LatLng(line.latitudes.first(), line.longitudes.first())
        val b = LatLng(line.latitudes.last(), line.longitudes.last())
        val avoidPoints = avoid.map { LatLng(it.latitude, it.longitude) }
        val avoidNames = avoid.map { it.name.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Candidate>()
        for (s in sights) {
            if (s.latitude.isNaN() || s.longitude.isNaN() || s.name.isBlank()) continue
            if (avoidNames.any { it.equals(s.name.trim(), ignoreCase = true) }) continue
            val weight = kindWeight(s.kind, culture)
            if (weight <= 0.0) continue
            val here = LatLng(s.latitude, s.longitude)
            if (here.distanceTo(a) < MIN_FROM_ENDS_M || here.distanceTo(b) < MIN_FROM_ENDS_M) continue
            if (avoidPoints.any { it.distanceTo(here) < MIN_FROM_ENDS_M }) continue
            val p = line.project(s.latitude, s.longitude)
            if (p.offM > radiusM) continue
            val closeness = 1.0 - p.offM / radiusM
            var value = weight * (0.55 + 0.45 * closeness)
            if (s.kind == ModuleSightKind.PASS && !s.elevationM.isNaN()) {
                value *= 1.0 + 0.3 * ((s.elevationM - 500.0) / 2000.0).coerceIn(0.0, 1.0)
            }
            out += Candidate(s, p.alongM, p.offM, value)
        }
        return out.sortedWith(
            // Values and distances are compared rounded, so floating-point noise never decides a tie.
            compareByDescending<Candidate> { Math.round(it.value * 1e6) }
                .thenBy { Math.round(it.offM) }
                .thenBy { it.sight.name }
                .thenBy { it.sight.latitude }
                .thenBy { it.sight.longitude },
        )
    }

    /** The greedy choice over [pool] without [banned]: the pool indices taken, in the order taken. */
    private fun pick(pool: List<Candidate>, line: RoadLine, wanted: Int, budget: Budget, banned: Set<Int>): List<Int> {
        val taken = ArrayList<Int>()
        val a = LatLng(line.latitudes.first(), line.longitudes.first())
        val b = LatLng(line.latitudes.last(), line.longitudes.last())
        val minApartAlong = MIN_SPACING_FRACTION * line.lengthM
        var km = 0.0
        var minutes = 0.0
        for (i in pool.indices) {
            if (taken.size >= wanted) break
            if (i in banned) continue
            val c = pool[i]
            val here = LatLng(c.sight.latitude, c.sight.longitude)
            if (here.distanceTo(a) < MIN_APART_M || here.distanceTo(b) < MIN_APART_M) continue
            val clash = taken.any {
                val o = pool[it]
                o.sight.name.trim().equals(c.sight.name.trim(), ignoreCase = true) ||
                    LatLng(o.sight.latitude, o.sight.longitude).distanceTo(here) < MIN_APART_M ||
                    kotlin.math.abs(o.alongM - c.alongM) < minApartAlong
            }
            if (clash) continue
            val extra = extraKm(c.offM)
            val extraMin = extraMinutes(extra)
            if (km + extra > budget.km || minutes + extraMin > budget.minutes) continue
            km += extra
            minutes += extraMin
            taken += i
        }
        return taken
    }
}

/** Where a point lies against a road: [alongM] metres from its start, [offM] metres away from it. */
internal class RoadProjection(val alongM: Double, val offM: Double, val segment: Int)

/** A polyline with its running length, to say how far along it a point is and how far off. */
internal class RoadLine(road: List<Pair<Double, Double>>) {
    val latitudes = DoubleArray(road.size) { road[it].first }
    val longitudes = DoubleArray(road.size) { road[it].second }

    /** Metres from the start to each point. */
    private val cumulative = DoubleArray(road.size)

    val lengthM: Double

    init {
        for (i in 1 until road.size) {
            cumulative[i] = cumulative[i - 1] +
                LatLng(latitudes[i - 1], longitudes[i - 1]).distanceTo(LatLng(latitudes[i], longitudes[i]))
        }
        lengthM = if (road.isEmpty()) 0.0 else cumulative.last()
    }

    /**
     * The nearest point of the road to ([lat], [lon]), looking only at segments from [fromSegment]
     * to [toSegment] (both inclusive): how far along it is and how far the point is from it. The nearest is picked on a plane
     * scaled at the point's latitude; the distances are then haversine.
     */
    fun project(lat: Double, lon: Double, fromSegment: Int = 0, toSegment: Int = Int.MAX_VALUE): RoadProjection {
        val n = latitudes.size
        if (n == 0) return RoadProjection(0.0, Double.MAX_VALUE, 0)
        if (n == 1) {
            return RoadProjection(0.0, LatLng(latitudes[0], longitudes[0]).distanceTo(LatLng(lat, lon)), 0)
        }
        val c = cos(Math.toRadians(lat))
        var bestSeg = min(max(fromSegment, 0), n - 2)
        val last = min(max(toSegment, bestSeg), n - 2)
        var bestT = 0.0
        var bestD2 = Double.MAX_VALUE
        for (i in bestSeg..last) {
            val x1 = (longitudes[i] - lon) * c
            val y1 = latitudes[i] - lat
            val dx = (longitudes[i + 1] - longitudes[i]) * c
            val dy = latitudes[i + 1] - latitudes[i]
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (-(x1 * dx + y1 * dy) / len2).coerceIn(0.0, 1.0)
            val px = x1 + t * dx
            val py = y1 + t * dy
            val d2 = px * px + py * py
            if (d2 < bestD2) {
                bestD2 = d2; bestSeg = i; bestT = t
            }
        }
        val onRoad = LatLng(latitudes[bestSeg], longitudes[bestSeg])
            .interpolateTo(LatLng(latitudes[bestSeg + 1], longitudes[bestSeg + 1]), bestT)
        val along = cumulative[bestSeg] + bestT * (cumulative[bestSeg + 1] - cumulative[bestSeg])
        return RoadProjection(along, onRoad.distanceTo(LatLng(lat, lon)), bestSeg)
    }
}
