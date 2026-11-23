// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock

import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleMapLayer
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost
import io.motohub.paddock.engine.PaddockEngine
import io.motohub.paddock.map.RidersLayer
import io.motohub.paddock.ui.PaddockApp

class PaddockEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = PaddockModule(host)
}

private class PaddockModule(private val host: MotoHubModuleHost) : MotoHubModule {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "paddock",
        version = "0.5.12",
        contractVersion = 39,
        entryClass = "io.motohub.paddock.PaddockEntry",
        displayName = "Paddock",
        description = "Ride together, privately: a QR to open a room, everyone on the NAV map. No account, no server."
    )

    private val engine = PaddockEngine(host)
    private val navMenu = ModuleFeaturePlacement.values().firstOrNull { it.name == "NAV_MENU" }
    private val riders = RidersLayer(host, engine, FEATURE)
    // By lazy: its class implements ModuleRideShortcut (contract 46), which an older app lacks.
    private val rideShortcut by lazy { io.motohub.paddock.map.RideShortcut(host.storage, engine, FEATURE) }

    private val features = object : ModuleFeatures {
        override fun features() = listOfNotNull(
            ModuleFeature(
                id = FEATURE,
                title = "Paddock",
                description = "Ride together, privately",
                placement = ModuleFeaturePlacement.MODULES,
                screen = { onBack -> PaddockApp(engine, host, onBack) }
            ),
            // First in the NAV's app menu (contract 45), the way back to the room during a ride.
            // By name: on an older app the constant does not exist and the entry is left out.
            navMenu?.let {
                ModuleFeature(
                    id = NAV_FEATURE,
                    title = "Open Paddock",
                    description = "Your room, its QR code and the riders",
                    placement = it,
                    screen = { onBack -> PaddockApp(engine, host, onBack) }
                )
            }
        )
    }

    init {
        host.log.log("PADDOCK loaded")
        // A rider in any room is reachable as soon as MOTO-HUB opens: joins, chat and the mailbox
        // arrive without opening Paddock. With no room, nothing connects anywhere.
        val rooms = java.io.File(host.storage, "rooms").listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        if (rooms.isNotEmpty() || java.io.File(host.storage, "ride.json").isFile ||
            java.io.File(host.storage, "room.json").isFile
        ) {
            engine.ensureStarted()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        ModuleMapLayer::class.java -> riders as T
        // By name: naming ModuleRideShortcut::class here would fail on an app before contract 46.
        else -> if (type.name == RIDE_SHORTCUT) rideShortcut as T else null
    }

    override fun release() {
        engine.release()
        host.log.log("PADDOCK released (Python stays loaded until the app restarts)")
    }

    private companion object {
        const val FEATURE = "paddock"
        const val NAV_FEATURE = "paddock-nav"
        const val RIDE_SHORTCUT = "io.motohub.android.module.ModuleRideShortcut"
    }
}
