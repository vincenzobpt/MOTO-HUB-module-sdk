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
        version = "0.5.23",
        contractVersion = 39,
        entryClass = "io.motohub.paddock.PaddockEntry",
        displayName = "Paddock",
        description = "Ride together, privately: a QR to open a room, everyone on the NAV map. No account."
    )

    private val engine = PaddockEngine(host)
    private val navMenu = ModuleFeaturePlacement.values().firstOrNull { it.name == "NAV_MENU" }
    private val riders = RidersLayer(host, engine, FEATURE)
    // By lazy: its class implements ModuleRideShortcut (contract 46), which an older app lacks.
    private val rideShortcut by lazy { io.motohub.paddock.map.RideShortcut(host.storage, engine, FEATURE) }

    // By lazy, by name: ModuleGroupRide is contract 48, which an older app lacks.
    private val groupRide by lazy { io.motohub.paddock.engine.GroupRideLink(engine) }

    // By lazy, by name: ModulePushReceiver is contract 51, which an older app lacks.
    private val pushReceiver by lazy { io.motohub.paddock.engine.PushReceiver(host.storage, engine.notifier) }

    // By lazy, by name: ModuleStatus is contract 50, which an older app lacks.
    private val statusLink = lazy { io.motohub.paddock.engine.StatusLink(engine) }

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
            // One hidden page per room (NONE: shown nowhere, opened by id), so a notification about
            // a room opens that room. The ids come from the room files too: at a cold start from
            // the notification the engine has not read the rooms yet.
            *roomIds().map { roomId ->
                ModuleFeature(
                    id = ROOM_FEATURE_PREFIX + roomId,
                    title = "Paddock room",
                    description = "A room in Paddock",
                    placement = ModuleFeaturePlacement.NONE,
                    screen = { onBack -> PaddockApp(engine, host, onBack, startRoom = roomId) }
                )
            }.toTypedArray(),
            // And its chat, for a notification about a message.
            *roomIds().map { roomId ->
                ModuleFeature(
                    id = CHAT_FEATURE_PREFIX + roomId,
                    title = "Paddock chat",
                    description = "A room's chat in Paddock",
                    placement = ModuleFeaturePlacement.NONE,
                    screen = { onBack -> PaddockApp(engine, host, onBack, startRoom = roomId, startChat = true) }
                )
            }.toTypedArray(),
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

    private fun roomIds(): Set<String> =
        engine.state.value.rooms.map { it.id }.toSet() +
            java.io.File(host.storage, "rooms").list().orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }

    init {
        host.log.log("PADDOCK loaded")
        // Pushes that woke this phone before Android ended MOTO-HUB (what the chat shows, who to answer).
        io.motohub.paddock.engine.Push.attach(host.storage)
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
        else -> when (type.name) {
            RIDE_SHORTCUT -> rideShortcut as T
            GROUP_RIDE -> groupRide as T
            STATUS -> statusLink.value as T
            PUSH_RECEIVER -> pushReceiver as T
            else -> null
        }
    }

    override fun release() {
        if (statusLink.isInitialized()) statusLink.value.release()
        engine.release()
        host.log.log("PADDOCK released (Python stays loaded until the app restarts)")
    }

    private companion object {
        const val FEATURE = "paddock"
        const val NAV_FEATURE = "paddock-nav"
        const val RIDE_SHORTCUT = "io.motohub.android.module.ModuleRideShortcut"
        /** Followed by a room id; Notifier builds the same ids. */
        const val ROOM_FEATURE_PREFIX = "room:"
        const val CHAT_FEATURE_PREFIX = "chat:"
        const val STATUS = "io.motohub.android.module.ModuleStatus"
        const val GROUP_RIDE = "io.motohub.android.module.ModuleGroupRide"
        const val PUSH_RECEIVER = "io.motohub.android.module.ModulePushReceiver"
    }
}
