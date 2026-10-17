// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * How a module puts its own features into the app.
 *
 * The app ships no knowledge of what any module does - not a screen, not a settings row, not a
 * name. It offers places where a feature can appear, and a loaded module fills them. Before that
 * module is installed there is nothing to see, because there is nothing in the app to show.
 *
 * This is what makes the module more than a receiver behind an existing UI: it arrives with its
 * own way in. Compose types resolve to the app's copy on both sides - the module compiles against
 * them and the app provides them - so a `@Composable` crossing this boundary is an ordinary call.
 */

/** Where the app is willing to show something a module brought with it. */
enum class ModuleFeaturePlacement {
    /**
     * A page about the module itself, reached from its card under Modules.
     *
     * Where a rider goes when they want to know what a module is, what it is doing, or whether it
     * is working - which is not Settings. Settings is for choices; this is for the thing itself,
     * and it sits next to the buttons that install and remove it.
     */
    MODULES,

    /**
     * A row in Settings, opening [ModuleFeature.screen] full-screen. For a genuine setting the
     * module adds - a choice a rider makes - not for a page describing the module.
     */
    SETTINGS,

    /**
     * A way to project onto the motorcycle, listed with the app's own. This is the placement a
     * feature takes when it drives the screen on the bike rather than the phone.
     */
    RIDE_MODE,

    /**
     * Somewhere only the module's own screens reach: not shown anywhere by the app, but
     * addressable by id through [MotoHubModuleHost.openFeature]. For the second and third
     * screens of a feature whose first one is already placed.
     */
    NONE,

    /**
     * An action on one recorded ride, on that trip's page. [MotoHubModuleHost.openedFor] says
     * which ride when [ModuleFeature.screen] opens.
     */
    RIDE_ACTION,

    /**
     * An action on one route - a saved one, or one just planned and previewed - on the route's
     * preview. [MotoHubModuleHost.openedFor] says which route.
     */
    ROUTE_ACTION,

    /**
     * A button at the top of Trips, beside the page's title (contract 14): the way in to what a
     * module keeps of the rides - its own list of what it made from them. Drawn by the app as a
     * small pill with [ModuleFeature.title].
     */
    TRIPS_HEADER,

    /**
     * A key on the NAV map that opens a panel over it (contract 32), the map still alive above:
     * for something a rider glances at while the map is open. The app draws the key, a small
     * pill with [ModuleFeature.title], and the panel's frame; [ModuleFeature.screen] fills it
     * and its onBack closes it. Phone only, like everything on the NAV map.
     */
    NAV_PANEL
}

/**
 * One thing a module offers a rider.
 *
 * [title] and [description] are the module's words, drawn wherever [placement] puts them, so the
 * app can describe a feature it knows nothing about. [screen] is called with a way back; what it
 * draws is entirely the module's business.
 */
class ModuleFeature(
    val id: String,
    val title: String,
    val description: String,
    val placement: ModuleFeaturePlacement,
    val screen: @Composable (onBack: () -> Unit) -> Unit
)

/**
 * The capability a module offers when it has features to contribute. Asked for by the app the
 * moment a module loads, and again after an install, so what a rider sees follows what is there.
 */
interface ModuleFeatures {
    fun features(): List<ModuleFeature>
}

/**
 * Something a module is doing right now that the rider should be able to see at a glance -
 * sending the position, recording - drawn by the app as a small animated pill at the top of the
 * Ride page for as long as [live] holds a value (contract 27). Answered as a capability; a module
 * with nothing live to show does not answer it.
 *
 * The app draws the pill, not the module: every live pill reads as one family wherever it comes
 * from, and a module cannot fill the Ride page with its own drawing.
 */
interface ModuleLiveStatus {
    /** What is live now, or null while nothing is. Read on the main thread; set from anywhere. */
    val live: StateFlow<ModuleLiveState?>
}

class ModuleLiveState(
    /** The module's own words, short enough for a pill: "Dawarich · live". */
    val label: String,
    /** The module's feature the pill opens when tapped, or null when it opens nothing. */
    val featureId: String? = null
)
