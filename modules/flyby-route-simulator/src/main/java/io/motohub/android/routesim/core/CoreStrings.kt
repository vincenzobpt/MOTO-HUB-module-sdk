// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The words the domain layer puts in front of the rider (errors and progress), in one place so
// another language can be added without touching the logic.
package io.motohub.android.routesim.core

import java.util.Locale

internal object CoreStrings {
    const val NEED_TWO_STOPS = "Add at least a start and a destination."
    const val PLANNING_ROUTE = "Planning the route…"
    const val FETCHING_LIMITS = "Looking up the speed limits…"
    const val FETCHING_ELEVATIONS = "Looking up the elevations…"
    const val NAMING_PLACES = "Naming the start and the destination…"
    const val ROUTE_EMPTY = "The route has no length. Move the start or the destination."
    const val ROUTE_FAILED = "The route could not be planned."
    const val NO_NETWORK = "No internet connection. Planning a route needs one."
    const val RATE_LIMITED = "The routing server is busy. Wait a minute and try again."
    const val NO_API_KEY = "Routing needs an API key. Add yours in the app's navigation settings."
    const val TOO_LONG_GENERIC = "This route is too long. The simulator handles routes up to 500 km."
    const val SAVE_FAILED = "The ride could not be saved."
    const val SAVE_INCONSISTENT = "The generated ride is inconsistent and was not saved."

    fun tooLong(km: Int, maxKm: Int): String =
        "This route is about $km km long. The simulator handles routes up to $maxKm km."

    fun kindMessage(kind: Int): String = when (kind) {
        io.motohub.android.module.ModuleRouteError.NO_NETWORK -> NO_NETWORK
        io.motohub.android.module.ModuleRouteError.RATE_LIMITED -> RATE_LIMITED
        io.motohub.android.module.ModuleRouteError.NO_API_KEY -> NO_API_KEY
        io.motohub.android.module.ModuleRouteError.TOO_LONG -> TOO_LONG_GENERIC
        else -> ROUTE_FAILED
    }

    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.ROOT, "%.4f, %.4f", latitude, longitude)
}
