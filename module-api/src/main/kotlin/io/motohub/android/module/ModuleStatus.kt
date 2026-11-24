// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import kotlinx.coroutines.flow.StateFlow

/**
 * How a module is doing, shown on its card in Settings ▸ Modules (contract 50): "Online",
 * "Connected", "Not connected". Unlike [ModuleLiveStatus], which is a pill on the Ride page only
 * while something is live, this is there all the time, quiet states included, and nowhere else.
 *
 * The app draws it, not the module: a coloured mark by [ModuleStatusState.level] and the module's
 * own words. Answered as a capability; a module with no state worth showing does not answer it.
 */
interface ModuleStatus {
    /** The state now, or null for nothing to say. Read on the main thread; set from anywhere. */
    val status: StateFlow<ModuleStatusState?>
}

class ModuleStatusState(
    /** How it reads at a glance: the colour of the mark, and whether it pulses. */
    val level: ModuleStatusLevel,
    /** One or two words, the module's own: "Online", "Connected", "Recording". */
    val label: String,
    /** An optional line under it: "Sunday group · 4 riders", "Battery 81%". */
    val detail: String? = null
)

enum class ModuleStatusLevel {
    /** Working and in touch: green, pulsing. */
    ACTIVE,

    /** Working, nothing happening: green, still. */
    READY,

    /** Trying to get there: searching, connecting. Amber. */
    CONNECTING,

    /** Needs the rider: an error, a permission, a login. Red. */
    ATTENTION,

    /** Off, or not connected. Grey. */
    OFF
}
