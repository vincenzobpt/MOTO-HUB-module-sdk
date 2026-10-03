// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget

/**
 * A flat map the app draws and a module directs (contract 22): the app's own map engine, style
 * and tiles, with the lines and pins the module gives it and the touches it reports back.
 */
interface ModuleMapHost {

    /** A new map. The caller owns it and must [close][ModuleMap.close] it. */
    fun open(): ModuleMap
}

interface ModuleMap {

    /** Draws the map in whatever space its parent gives it. */
    @Composable
    @ComposableTarget(ModuleUi.UI_APPLIER)
    fun Surface()

    /** Replaces every line. */
    fun setLines(lines: Array<ModuleMapLine>)

    /** Replaces every pin. */
    fun setPins(pins: Array<ModuleMapPin>)

    /** Frames the given points. */
    fun fit(latitudes: DoubleArray, longitudes: DoubleArray)

    fun center(latitude: Double, longitude: Double, zoom: Double)

    /** Where touches are reported; null stops reporting. */
    fun setListener(listener: ModuleMapListener?)

    /** Frees the map. */
    fun close()
}

class ModuleMapLine(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val colorArgb: Int,
    val widthDp: Float,
    val dashed: Boolean
)

class ModuleMapPin(
    val latitude: Double,
    val longitude: Double,
    val colorArgb: Int,
    val label: String
)

interface ModuleMapListener {
    fun onTap(latitude: Double, longitude: Double)
    fun onLongPress(latitude: Double, longitude: Double)
    /** [index] is the pin's position in the array last given to [ModuleMap.setPins]. */
    fun onPinTap(index: Int)
}
