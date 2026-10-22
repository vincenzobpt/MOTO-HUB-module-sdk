// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.mapopt

import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleMapboxAccount
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost

class MapOptEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = MapOptModule(host)
}

/**
 * Map options the rider brings with their own accounts. Today one: a Mapbox token, which turns the
 * 3D POV onto Mapbox Standard - terrain, trees, landmarks, light - drawn by Mapbox GL JS. The account is offered whether or not a token
 * is set; it answers null until there is one, and the app keeps its own map.
 */
private class MapOptModule(private val host: MotoHubModuleHost) : MotoHubModule {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "mapopt",
        version = "0.2.0",
        contractVersion = 36,
        entryClass = "io.motohub.mapopt.MapOptEntry",
        displayName = "MapOpt",
        description = "Your own Mapbox token for the 3D POV: Mapbox Standard with terrain, trees, landmarks and light."
    )

    private val account = MapboxAccount(host)

    private val features = object : ModuleFeatures {
        override fun features(): List<ModuleFeature> = listOf(
            ModuleFeature(
                id = "mapopt-home",
                title = "MapOpt",
                description = "Your Mapbox token for the 3D POV",
                placement = ModuleFeaturePlacement.MODULES,
                screen = { onBack -> HomePage(host, account, onBack) }
            )
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        ModuleMapboxAccount::class.java -> account as T
        else -> null
    }

    override fun release() = Unit
}
