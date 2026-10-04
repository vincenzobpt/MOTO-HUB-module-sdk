// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// What the rider asks of the experiences. Plain Kotlin, no Android.
package io.motohub.android.routesim.experiences

/**
 * The filters of the Experiences page, one set per country. A target slider (0..100) that is null is
 * "don't mind": it neither favours nor penalises anything.
 */
data class ExperienceFilters(
    val minKm: Int = DEFAULT_MIN_KM,
    val maxKm: Int = DEFAULT_MAX_KM,
    val minMinutes: Int = DEFAULT_MIN_MINUTES,
    val maxMinutes: Int = DEFAULT_MAX_MINUTES,
    /** 0 = city, 100 = nature. Chooses which experiences are shown. */
    val nature: Int? = null,
    /** 0 = fast roads, 100 = all bends. */
    val twisty: Int? = null,
    /** 0 = tarmac only, 100 = gravel. */
    val gravel: Int? = null,
    /** 0 = scenery (viewpoints, passes), 100 = culture (castles, abbeys). Chooses the detours only. */
    val culture: Int? = null,
    /** A region name of the country's pack, or null for all. */
    val region: String? = null,
    /** How many detours to add, 0..[MAX_DETOURS]; the km and time limits can still lower it. */
    val detours: Int = DEFAULT_DETOURS,
) {
    companion object {
        const val DEFAULT_MIN_KM = 0
        const val DEFAULT_MAX_KM = 500
        const val DEFAULT_MIN_MINUTES = 0
        const val DEFAULT_MAX_MINUTES = 900
        const val DEFAULT_DETOURS = 3
        const val MAX_DETOURS = 6
        val DEFAULT = ExperienceFilters()
    }
}

/** What a shown experience does not satisfy of the filters, for the label on its card. */
sealed class Mismatch {
    /** Longer than the filter's maximum by [km], or shorter than its minimum by [km] when negative. */
    data class Km(val km: Int) : Mismatch()
    data class Minutes(val minutes: Int) : Mismatch()
    data object Nature : Mismatch()
    data object Twisty : Mismatch()
    data object Gravel : Mismatch()
    data object Region : Mismatch()
}

/** An experience of the pack as the page shows it: how well it fits, what it misses, and its tags. */
data class RankedExperience(
    val experience: Experience,
    /** 0.0 (far) .. 1.0 (every filter met). */
    val score: Double,
    val mismatches: List<Mismatch>,
    /** Short labels in English, e.g. "Nature", "Bends", "Gravel", "Mountain". */
    val tags: List<String>,
)
