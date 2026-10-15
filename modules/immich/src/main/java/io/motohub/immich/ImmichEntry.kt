// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleMapLayer
import io.motohub.android.module.ModuleMapLayerPin
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost

class ImmichEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = ImmichModule(host)
}

private class ImmichModule(private val host: MotoHubModuleHost) : MotoHubModule {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "immich",
        version = "0.2.0",
        contractVersion = 30,
        entryClass = "io.motohub.immich.ImmichEntry",
        displayName = "Immich",
        description = "Shows the photos of your own Immich server on the NAV map."
    )

    private val server = ImmichServer(host)
    private val photos = PhotoMap(host, server)
    private val features = ImmichFeatures(host, server, photos)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        ModuleMapLayer::class.java -> photos as T
        else -> null
    }

    override fun release() = Unit
}

/** The photos as pins on the NAV map; a tap opens the photo's page. */
private class PhotoMap(private val host: MotoHubModuleHost, private val server: ImmichServer) : ModuleMapLayer {

    override val title = "Immich photos"

    // The spots of the last answer, by pin id: what a tap is looked up in.
    @Volatile private var spots: Map<String, Spot> = emptyMap()

    /** The spot whose page is open, or about to be. */
    @Volatile var open: Spot? = null

    override fun pinsNear(latitude: Double, longitude: Double, radiusMeters: Int, limit: Int): Array<ModuleMapLayerPin> {
        if (!server.configured) return emptyArray()
        val found = Markers.near(server.markers(), latitude, longitude, radiusMeters, limit)
        spots = found.associateBy { it.marker.id }
        return found.map {
            ModuleMapLayerPin(
                id = it.marker.id,
                latitude = it.marker.latitude,
                longitude = it.marker.longitude,
                colorArgb = PIN_COLOR,
                symbol = "📷",
                label = if (it.ids.size > 1) it.ids.size.toString() else ""
            )
        }.toTypedArray()
    }

    override fun onPinTap(pinId: String) {
        open = spots[pinId] ?: return
        host.openFeature(PHOTO_FEATURE)
    }
}

private class ImmichFeatures(
    private val host: MotoHubModuleHost,
    private val server: ImmichServer,
    private val photos: PhotoMap
) : ModuleFeatures {

    override fun features(): List<ModuleFeature> = listOf(
        ModuleFeature(
            id = "immich-settings",
            title = "Immich",
            description = "Your Immich server and its photos on the NAV map",
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> SettingsPage(host, server, onBack) }
        ),
        ModuleFeature(
            id = PHOTO_FEATURE,
            title = "Photo",
            description = "A photo from Immich",
            placement = ModuleFeaturePlacement.NONE,
            screen = { onBack -> PhotoViewer(host, server, photos.open, onBack) }
        )
    )
}

private const val PHOTO_FEATURE = "immich-photo"
internal const val PIN_COLOR = 0xFF4250AF.toInt()
