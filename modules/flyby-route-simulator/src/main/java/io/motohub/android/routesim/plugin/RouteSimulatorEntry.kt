// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The module's front door: what the app loads by name and everything it can reach from there.
package io.motohub.android.routesim.plugin

import io.motohub.android.module.ModuleExtension
import io.motohub.android.module.ModuleExtensions
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost
import io.motohub.android.routesim.ui.AboutScreen
import io.motohub.android.routesim.ui.CALIBRATE_FEATURE_ID
import io.motohub.android.routesim.ui.RouteSimulatorApp
import io.motohub.android.routesim.ui.RsEnv
import io.motohub.android.routesim.ui.SIMULATE_EXTENSION_ID
import io.motohub.android.routesim.ui.SIMULATE_FEATURE_ID
import io.motohub.android.routesim.ui.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The class the app names in the module manifest, built reflectively and the only one that is. */
class RouteSimulatorEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = RouteSimulatorModule(host)
}

/** The manifest the module states about itself; the build compares it with the module package. */
internal val routeSimulatorManifest = ModuleManifest(
    id = "flyby-route-simulator",
    version = "0.1.2",
    contractVersion = 22,
    entryClass = "io.motohub.android.routesim.plugin.RouteSimulatorEntry",
    displayName = "Flyby Route Simulator",
    description = "Plans a route and rides it virtually, producing a trip the app can play back."
)

private class RouteSimulatorModule(private val host: MotoHubModuleHost) : MotoHubModule {

    override val manifest = routeSimulatorManifest

    // Everything that runs off the main thread - planning, riding, saving - runs here, so
    // releasing the module stops it; the maps are the app's, so they are closed with it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val env = RsEnv(host, scope)

    private val features = object : ModuleFeatures {
        override fun features(): List<ModuleFeature> = listOf(
            // Under Modules: what the module is and whether it can run.
            ModuleFeature(
                id = "about",
                title = Strings.ABOUT_TITLE,
                description = Strings.ABOUT_DESCRIPTION,
                placement = ModuleFeaturePlacement.MODULES,
                screen = { onBack -> AboutScreen(env, onBack) }
            ),
            // Shown nowhere by the app: reached from the page above, and from Flyby.
            simulateFeature(SIMULATE_FEATURE_ID),
            // Also shown nowhere: reached from the page above.
            ModuleFeature(
                id = CALIBRATE_FEATURE_ID,
                title = Strings.CALIBRATE_TITLE,
                description = Strings.CALIBRATE_DESCRIPTION,
                placement = ModuleFeaturePlacement.NONE,
                screen = { onBack -> RouteSimulatorApp(env, true, onBack) }
            )
        )
    }

    // What this module adds to Flyby's own page: a way in to the planner.
    private val extensions = object : ModuleExtensions {
        override fun extensions(): List<ModuleExtension> = listOf(
            ModuleExtension(targetModuleId = "motohub-flyby", feature = simulateFeature(SIMULATE_EXTENSION_ID))
        )
    }

    private fun simulateFeature(id: String): ModuleFeature = ModuleFeature(
        id = id,
        title = Strings.simulateTitle,
        description = Strings.SIMULATE_DESCRIPTION,
        placement = ModuleFeaturePlacement.NONE,
        screen = { onBack -> RouteSimulatorApp(env, false, onBack) }
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        ModuleExtensions::class.java -> extensions as T
        else -> null
    }

    override fun release() {
        try { scope.cancel() } catch (_: Throwable) { }
        try { env.closeAllMaps() } catch (_: Throwable) { }
    }
}
