// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * Pins a module adds to the NAV map (contract 30): photos, places from a service of the rider's,
 * anything that has a position. Answered as a capability; a module with nothing to put on the map
 * does not answer it.
 *
 * The app draws the pins, not the module, so they read as one family with the map's own, and it
 * lists the layer by [title] in the map's layers menu, where the rider switches it on and off.
 */
interface ModuleMapLayer {

    /** The layer's name in the layers menu, in the module's words: "Immich photos". */
    val title: String

    /**
     * The pins within [radiusMeters] of ([latitude], [longitude]), at most [limit] of them; an
     * empty array for none. Asked each time the map settles somewhere new, on a background thread:
     * it may block on the network, and the answer replaces the previous one.
     */
    fun pinsNear(latitude: Double, longitude: Double, radiusMeters: Int, limit: Int): Array<ModuleMapLayerPin>

    /** The rider tapped the pin filed under [pinId]. On the main thread; usually opens a feature with host.openFeature. */
    fun onPinTap(pinId: String)

    /**
     * A picture for the pin filed under [pinId] (contract 31), about [sizePx] on its shorter side:
     * the flat map draws it as a round photo instead of the pin's [ModuleMapLayerPin.symbol]. Null
     * keeps the symbol. Asked off the main thread, it may block on the network; the app keeps what
     * it gets, so the same pin is not asked again while the rider pans.
     */
    fun pinImage(pinId: String, sizePx: Int): android.graphics.Bitmap? = null
}

class ModuleMapLayerPin(
    /** The module's own name for it, handed back to [ModuleMapLayer.onPinTap]. */
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val colorArgb: Int,
    /** One emoji drawn inside the pin: "📷". */
    val symbol: String,
    /** Drawn beside the pin on the flat map, or as a small badge on its photo (contract 31); empty for none. */
    val label: String
)
