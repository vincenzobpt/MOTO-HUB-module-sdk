// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.tomtom

import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleGeocoder
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleMapLayer
import io.motohub.android.module.ModuleMapOverlay
import io.motohub.android.module.ModulePlaceProvider
import io.motohub.android.module.ModuleTravelTimes
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost

class TomTomEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = TomTomModule(host)
}

internal const val HOME_FEATURE = "tomtom-home"
internal const val ROAD_FEATURE = "tomtom-road"
internal const val INCIDENT_FEATURE = "tomtom-incident"

/**
 * TomTom's data in the NAV, on the phone only. Every capability is offered whether or not the
 * rider has set the module up: it answers with nothing until they have, so the app needs no
 * state of its own about it.
 */
private class TomTomModule(private val host: MotoHubModuleHost) : MotoHubModule {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "tomtom",
        version = "0.1.0",
        contractVersion = 32,
        entryClass = "io.motohub.tomtom.TomTomEntry",
        displayName = "TomTom",
        description = "Live traffic, places, search and road info from your own free TomTom key — on your phone only."
    )

    private val service = TomTomService(host)
    private val incidents = IncidentLayer(host, service)
    private val features = TomTomFeatures(host, service, incidents)
    private val overlay = TrafficOverlay(service)
    private val places = TomTomPlaces(service)
    private val search = TomTomSearch(service)
    private val drive = TomTomDrive(service)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        ModuleMapOverlay::class.java -> overlay as T
        ModuleMapLayer::class.java -> incidents as T
        ModulePlaceProvider::class.java -> places as T
        ModuleGeocoder::class.java -> search as T
        ModuleTravelTimes::class.java -> drive as T
        else -> null
    }

    override fun release() = Unit
}

private class TomTomFeatures(
    private val host: MotoHubModuleHost,
    private val service: TomTomService,
    private val incidents: IncidentLayer
) : ModuleFeatures {

    override fun features(): List<ModuleFeature> = listOf(
        ModuleFeature(
            id = HOME_FEATURE,
            title = "TomTom",
            description = "Traffic, places, search and road info from your own TomTom key",
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> HomePage(host, service, onBack) }
        ),
        ModuleFeature(
            id = ROAD_FEATURE,
            title = "Road info",
            description = "The road under you and its speed limit",
            placement = ModuleFeaturePlacement.NAV_PANEL,
            screen = { onBack -> RoadPanel(host, service, onBack) }
        ),
        ModuleFeature(
            id = INCIDENT_FEATURE,
            title = "Traffic incident",
            description = "An incident from TomTom",
            placement = ModuleFeaturePlacement.NONE,
            screen = { onBack -> IncidentPage(host, incidents.open, onBack) }
        )
    )
}
