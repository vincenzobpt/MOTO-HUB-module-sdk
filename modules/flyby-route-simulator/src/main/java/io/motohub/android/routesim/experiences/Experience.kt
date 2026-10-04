// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The curated catalogue's values: a place, an experience, a country's pack. Plain Kotlin, no Android.
package io.motohub.android.routesim.experiences

/** A named point of the catalogue. */
data class ExperiencePlace(val name: String, val latitude: Double, val longitude: Double)

/**
 * One curated ride from [from] to [to]. [km] and [minutes] are typical, without detours, checked
 * against the router by the catalogue tool. [nature], [twisty] and [gravel] run 0..100: 0 is all
 * city / fast roads / all tarmac.
 */
data class Experience(
    /** `<country>-<kebab>`, unique in the country and stable: the key of caches and saved filters. */
    val id: String,
    val name: String,
    val description: String,
    val region: String,
    val from: ExperiencePlace,
    val to: ExperiencePlace,
    /** Places that force the right road, passed in order; no stops. 0..4. */
    val via: List<ExperiencePlace>,
    val km: Double,
    val minutes: Double,
    val nature: Int,
    val twisty: Int,
    val gravel: Int,
    val maxElevationM: Int,
    /** 12..40 points of (latitude, longitude) outlining the ride, drawn as the card's small map. */
    val shape: List<Pair<Double, Double>>,
)

/** One country's experiences. [version] counts the pack's revisions: the higher one wins. */
data class CountryPack(
    val country: String,
    val name: String,
    val version: Int,
    val experiences: List<Experience>,
)
