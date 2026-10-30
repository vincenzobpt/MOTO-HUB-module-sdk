// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

/**
 * The app's diagnostics report, for a module that has just caught something worth a look
 * (contract 38): the rider's Support ID, and the report sent now, the way "Send diagnostics now"
 * in Diagnostics sends it. Everything the module wrote to [MotoHubModuleHost.log] travels with it.
 *
 * An optional interface: the app's [MotoHubModuleHost] implements it from contract 38 on, and a
 * module reaches it with `as?`. A module that must still load into older apps looks the name up
 * first (`Class.forName`): on an app without it, naming the type at all fails to resolve.
 */
interface ModuleDiagnosticReports {

    /** False in a build with no collector to send to: then a module offers no button at all. */
    val canSendReports: Boolean

    /** The rider's Support ID, as Diagnostics shows it: what they quote when they ask for help. */
    fun supportId(): String

    /**
     * Sends the report now. [reason] goes into the log above it, so whoever reads the report sees
     * why it was sent. [done] gets true once the collector has it, false when the first attempt
     * could not leave the phone - the app then keeps trying for a while on its own. Any thread.
     */
    fun sendReport(reason: String, done: (sent: Boolean) -> Unit)
}
