// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module


/**
 * What a module may do with the other modules (contract 22).
 */
interface ModuleBridge {

    /** Whether the module [moduleId] is installed. */
    fun isInstalled(moduleId: String): Boolean

    /**
     * Opens the feature [featureId] of another module on a ride or route, exactly as if the rider
     * had chosen it from that ride's page. [entry] must be one this module received from
     * [ModuleRideLibrary] - never one it built. Returns false when the module or feature is not
     * there. The rider's Back leaves that feature and lands on the screen beneath, not on the
     * caller.
     */
    fun openFeatureOn(moduleId: String, featureId: String, entry: ModuleRideEntry): Boolean

    /**
     * What other modules offer on this one: the entries a module declared with
     * [ModuleExtensions] and aimed at this module's id.
     */
    fun extensions(): List<ModuleExtensionEntry>

    /** Opens an entry from [extensions]. */
    fun openExtension(entry: ModuleExtensionEntry)
}

/** One thing another module offers on this one. */
class ModuleExtensionEntry(
    /** The module that offers it. */
    val moduleId: String,
    val featureId: String,
    val title: String,
    val description: String
)

/**
 * A capability a module answers to say "I add this to module [ModuleExtension.targetModuleId]".
 * The app lists the entries on the target module's card under Modules and hands them to the
 * target through [ModuleBridge.extensions].
 */
interface ModuleExtensions {
    fun extensions(): List<ModuleExtension>
}

class ModuleExtension(
    val targetModuleId: String,
    val feature: ModuleFeature
)
