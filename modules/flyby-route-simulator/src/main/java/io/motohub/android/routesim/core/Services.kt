// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.routesim.core

import io.motohub.android.module.ModuleBridge
import io.motohub.android.module.ModulePlaces
import io.motohub.android.module.ModuleRideLibrary
import io.motohub.android.module.ModuleRideWriter
import io.motohub.android.module.ModuleRouting
import io.motohub.android.module.MotoHubModuleHost

/**
 * The slice of the host the generator touches. Built from the host in production; a test builds
 * it from fakes of these five interfaces without a whole host.
 */
internal class Services(
    val routing: ModuleRouting,
    val places: ModulePlaces,
    val rideWriter: ModuleRideWriter,
    val modules: ModuleBridge,
    val rides: ModuleRideLibrary,
) {
    companion object {
        fun from(host: MotoHubModuleHost): Services =
            Services(host.routing, host.places, host.rideWriter, host.modules, host.rides)
    }
}
