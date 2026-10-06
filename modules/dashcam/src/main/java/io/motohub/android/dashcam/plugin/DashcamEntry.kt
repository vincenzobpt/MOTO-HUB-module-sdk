// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The module's front door: what the app loads by name and everything it can reach from there.
package io.motohub.android.dashcam.plugin

import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.ui.DashcamApp
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleProjection
import io.motohub.android.module.ModuleProjectionReach
import io.motohub.android.module.ModuleProjectionTile
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost

/** The class the app names in the module manifest, built reflectively and the only one that is. */
class DashcamEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = DashcamModule(host)
}

private class DashcamModule(private val host: MotoHubModuleHost) : MotoHubModule {

    override val manifest = ModuleManifest(
        id = "dashcam",
        version = "0.4.3",
        contractVersion = 20,
        entryClass = "io.motohub.android.dashcam.plugin.DashcamEntry",
        displayName = "Dashcam",
        description = "Shows a Wi-Fi dashcam on the phone - full-screen or in the dashboard's map panel - with its buttons, settings and files."
    )

    private val controller = DashcamController(host)

    private val projection = DashcamProjection(controller)

    /**
     * Two pages. The About page under Modules is where the rider sets the camera up and connects -
     * the app has no place for a module in the Ride tab without a motorcycle, and the module works
     * only without one. The live view is reached from there, and from a tap on the camera's panel
     * in the dashboard.
     */
    private val features = object : ModuleFeatures {
        override fun features(): List<ModuleFeature> = listOf(
            ModuleFeature(
                id = DashcamModuleIds.ABOUT,
                title = "Dashcam",
                description = "Connect to a Wi-Fi dashcam and watch it full-screen.",
                placement = ModuleFeaturePlacement.MODULES,
                screen = { onBack -> DashcamApp(host, controller, startLive = false, onExit = onBack) }
            ),
            ModuleFeature(
                id = DashcamModuleIds.LIVE,
                title = "Dashcam live",
                description = "The camera's picture, full-screen.",
                placement = ModuleFeaturePlacement.NONE,
                screen = { onBack -> DashcamApp(host, controller, startLive = true, onExit = onBack) }
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        ModuleProjection::class.java, ModuleProjectionReach::class.java -> projection as T
        ModuleProjectionTile::class.java -> DashcamTile as T
        else -> null
    }

    override fun release() = controller.release()
}

/** The module's page ids, as the app files them. */
internal object DashcamModuleIds {
    const val ABOUT = "about"
    const val LIVE = "live"
}
