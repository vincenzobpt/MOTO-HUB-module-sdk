// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Orders a country's experiences by how close they are to the filters and picks the best ones.
// Never empty for a non-empty pack: filters that are too narrow just show the nearest rides,
// each labelled with what it misses. Plain Kotlin, no Android.
package io.motohub.android.routesim.experiences

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object ExperienceRanker {

    /** A slider gap wider than this many points is reported as a mismatch. */
    private const val SLIDER_MISMATCH_GAP = 25

    /** Score kept by an experience of another region than the one asked for. */
    private const val REGION_FACTOR = 0.5

    private const val WEIGHT_RANGE = 2.0
    private const val WEIGHT_SLIDER = 1.0

    /** The widest a smooth penalty may be narrower than: a tight range still forgives a few units. */
    private const val MIN_KM_SCALE = 10.0
    private const val MIN_MINUTES_SCALE = 15.0
    private const val MAX_TAGS = 4

    /**
     * The [limit] experiences of [pack] that fit [filters] best, best first. Always
     * min([limit], pack size) of them; ties break by id so the order is the same every time.
     * Experiences of another region than the filter's come last, and only when the region has fewer
     * than [limit] of its own.
     */
    fun rank(pack: CountryPack, filters: ExperienceFilters, limit: Int = 10): List<RankedExperience> {
        val count = limit.coerceAtLeast(0)
        if (count == 0 || pack.experiences.isEmpty()) return emptyList()
        val region = filters.region?.trim()?.takeIf { it.isNotEmpty() }
        val ranked = pack.experiences.map { score(it, filters, region) }
        val byScore = compareByDescending<RankedExperience> { it.score }.thenBy { it.experience.id }
        val chosen = if (region == null) {
            ranked.sortedWith(byScore).take(count)
        } else {
            val (inside, outside) = ranked.partition { Mismatch.Region !in it.mismatches }
            val own = inside.sortedWith(byScore).take(count)
            (own + outside.sortedWith(byScore).take(count - own.size)).sortedWith(byScore)
        }
        return chosen
    }

    /** The regions of [pack], alphabetical, for the region selector. */
    fun regions(pack: CountryPack): List<String> =
        pack.experiences.map { it.region.trim() }.filter { it.isNotEmpty() }.distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

    /** Up to four short tags for the card, the most telling first. */
    fun tagsOf(e: Experience): List<String> {
        val tags = ArrayList<String>(MAX_TAGS)
        when {
            e.gravel >= 40 -> tags += "Gravel"
            e.gravel in 15..39 -> tags += "Mixed"
        }
        if (e.maxElevationM >= 1500) tags += "Mountain"
        if (e.twisty >= 65) tags += "Bends" else if (e.twisty <= 30) tags += "Fast"
        if (e.nature >= 65) tags += "Nature" else if (e.nature <= 35) tags += "City"
        if (e.km >= 300) tags += "Long" else if (e.km <= 80) tags += "Short"
        return tags.take(MAX_TAGS)
    }

    private fun score(e: Experience, f: ExperienceFilters, region: String?): RankedExperience {
        val mismatches = ArrayList<Mismatch>()
        var sum = 0.0
        var weight = 0.0

        val kmExcess = excess(e.km, f.minKm.toDouble(), f.maxKm.toDouble())
        sum += WEIGHT_RANGE * closeness(abs(kmExcess), f.minKm, f.maxKm, MIN_KM_SCALE)
        weight += WEIGHT_RANGE
        kmExcess.roundToInt().takeIf { it != 0 }?.let { mismatches += Mismatch.Km(it) }

        val minExcess = excess(e.minutes, f.minMinutes.toDouble(), f.maxMinutes.toDouble())
        sum += WEIGHT_RANGE * closeness(abs(minExcess), f.minMinutes, f.maxMinutes, MIN_MINUTES_SCALE)
        weight += WEIGHT_RANGE
        minExcess.roundToInt().takeIf { it != 0 }?.let { mismatches += Mismatch.Minutes(it) }

        val regionMiss = region != null && !e.region.trim().equals(region, ignoreCase = true)
        if (regionMiss) mismatches += Mismatch.Region

        fun slider(target: Int?, value: Int, mismatch: Mismatch) {
            if (target == null) return
            val gap = abs(target - value)
            sum += WEIGHT_SLIDER * (1.0 - gap.coerceAtMost(100) / 100.0)
            weight += WEIGHT_SLIDER
            if (gap > SLIDER_MISMATCH_GAP) mismatches += mismatch
        }
        slider(f.nature, e.nature, Mismatch.Nature)
        slider(f.twisty, e.twisty, Mismatch.Twisty)
        slider(f.gravel, e.gravel, Mismatch.Gravel)

        val score = (sum / weight * (if (regionMiss) REGION_FACTOR else 1.0)).coerceIn(0.0, 1.0)
        return RankedExperience(e, score, mismatches, tagsOf(e))
    }

    /** How far [v] lies beyond [lo]..[hi]: positive above, negative below, 0 inside. */
    private fun excess(v: Double, lo: Double, hi: Double): Double = when {
        v > hi -> v - hi
        v < lo -> v - lo
        else -> 0.0
    }

    /** 1 inside the range, falling smoothly with the distance outside, relative to the range's width. */
    private fun closeness(distance: Double, lo: Int, hi: Int, minScale: Double): Double {
        if (distance <= 0.0) return 1.0
        val scale = max(minScale, (hi - lo) * 0.25)
        return 1.0 / (1.0 + distance / scale)
    }
}
