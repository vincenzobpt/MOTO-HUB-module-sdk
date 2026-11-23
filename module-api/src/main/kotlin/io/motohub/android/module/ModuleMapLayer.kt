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

    /**
     * Above 0, the app asks [pinsNear] again every this many seconds while the map shows the layer
     * (contract 44), for pins that move on their own: riders of a group ride. 0, the default, asks
     * only when the map settles somewhere new. Read once, when the map opens.
     */
    val refreshSeconds: Int get() = 0

    /**
     * True for pins that are people or things that move, riders of a group ride (contract 45).
     * The flat NAV map draws them above everything else, never grouped with places or photos and
     * never hidden by a collision: each one is its [pinImage] (a round avatar) or, without one,
     * its coloured pin with its [ModuleMapLayerPin.symbol], and its [ModuleMapLayerPin.label]
     * beside it as a place's name. The 3D map draws them as places, ungrouped; Real 3D shows
     * no pins. The Ride Dashboard's maps on the motorcycle's screen draw them too (the flat,
     * MapLibre and 3D POV maps and the north-up mini map): a dot in [ModuleMapLayerPin.colorArgb]
     * with its label, asked every few seconds around the rider while the layer is on in the
     * layers menu. False, the default, keeps the photo layer Immich is. Read once, when the map opens.
     */
    val live: Boolean get() = false
}

class ModuleMapLayerPin(
    /** The module's own name for it, handed back to [ModuleMapLayer.onPinTap]. */
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val colorArgb: Int,
    /** One emoji drawn inside the pin: "📷". */
    val symbol: String,
    /**
     * Drawn beside the pin on the flat map, or as a small badge on its photo (contract 31); a
     * [ModuleMapLayer.live] layer's always beside it (contract 45). Empty for none.
     */
    val label: String
)
