// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aaplugin

import android.content.Context
import io.motohub.android.module.ModuleDashboardNetwork
import io.motohub.android.module.ModuleKeySink
import io.motohub.android.module.ModuleKeySinkRegistry
import io.motohub.android.module.ModuleProjectionSource
import io.motohub.android.module.ModuleSessionDetail
import io.motohub.android.module.ModuleProjectionSpec
import io.motohub.android.module.ModuleTouchAction
import io.motohub.android.module.ModuleVideoConfig
import io.motohub.android.module.ModuleVideoPreset
import java.io.InputStream

/**
 * The generic half of what this receiver is moved out to [io.motohub.android.module]: a surface to
 * draw into, a size, whether it can be touched, a running projection. None of that was ever
 * specific to Android Auto, and leaving it named after Android Auto is what put Android Auto's
 * vocabulary into parts of the app that have nothing to do with it.
 *
 * These aliases exist so the receiver's own code keeps reading in its own words.
 */
typealias AaTouchAction = ModuleTouchAction
typealias AaVideoPreset = ModuleVideoPreset
typealias AaVideoConfig = ModuleVideoConfig
typealias AaReceiverSpec = ModuleProjectionSpec
typealias AaReceiverPlugin = ModuleProjectionSource
typealias AaKeySink = ModuleKeySink
typealias AaKeySinkRegistry = ModuleKeySinkRegistry
typealias AaDashboardNetwork = ModuleDashboardNetwork

/**
 * The receiver's own vocabulary, inside the module that speaks it.
 *
 * This used to be a Gradle module in the app, because the app once called every one of these.
 * It no longer calls any: waking the far side, guidance and the USB probe became ordinary
 * capabilities in [io.motohub.android.module], and what was left described nothing the app
 * needs to know. It lives here now, so the only thing crossing the boundary is the generic
 * contract - and the app ships no Android Auto types at all, loaded module or not.
 *
 * Historical note on what this was.
 *
 * The receiver is a module the app fetches and loads through a class loader whose parent is the
 * app's own, so everything in this package resolves to the app's copy on both sides. The module
 * is a guest: it asks the host for a context, an identity, a place to register its key sink and
 * a log to write into, and it owns nothing outside the session it is asked to run. What crosses
 * this boundary is the whole of what the app knew about the receiver when it was compiled in -
 * every field, callback and query below is one the app already used, lifted out of the
 * receiver's own types so the app never names those again.
 *
 * Rule of the road, inherited from the CORE contract: **append, never insert or rename.** An
 * older module answering a newer app must fail on the call it lacks, not misbehave on one it
 * misreads. [AaPluginContract.CONTRACT_VERSION] is what the loader compares before trusting a
 * module with a session.
 */
object AaPluginContract {
    /** Bump whenever anything in this package changes shape. 1: the initial contract. */
    const val CONTRACT_VERSION = 1

    /**
     * The one class the loader looks up by name. It must implement [AaPluginEntry] and have a
     * public no-argument constructor; everything else about the module is reached from there.
     */
    const val ENTRY_CLASS = "io.motohub.android.aa.plugin.AaPluginEntry"

    /** The local port the receiver listens on for Google's Android Auto app. */
    const val RECEIVER_PORT = 5288

    /** Android Auto's own head unit server, the way in on releases that closed self-mode. */
    const val HEAD_UNIT_SERVER_PORT = 5277
}

/**
 * Turn-by-turn guidance parsed from the session's instrument-cluster navigation channel.
 * [maneuverType] carries the AA wire enum raw value; -1 when the app has not described the next
 * turn. Distances and times are -1 when the app did not send them.
 */
data class AaNavigationGuidance(
    val active: Boolean,
    val rerouting: Boolean = false,
    val maneuverType: Int = -1,
    val roundaboutExitNumber: Int = 0,
    val road: String = "",
    val distanceToManeuverMeters: Int = -1,
    val timeToManeuverSeconds: Int = -1,
    val distanceRemainingMeters: Int = -1,
    val timeToArrivalSeconds: Long = -1L,
    val estimatedTimeAtArrival: String = ""
)

fun interface AaNavigationGuidanceListener {
    fun onGuidance(guidance: AaNavigationGuidance)
}

/**
 * Where the module's log lines go. The app routes them into the same event log everything else
 * writes, so a rider's diagnostics report is still one merged log - a report that goes quiet at
 * the module boundary explains nothing.
 */
fun interface AaLogSink {
    fun log(line: String)
}

/**
 * The head-unit identity Android Auto is shown: a certificate and its private-key material. The
 * identity is the app's, staged into its resources at build time; the receiver borrows it for
 * the SSL handshake and never learns where it came from.
 */
interface AaIdentityProvider {
    fun isAvailable(): Boolean
    fun openCertificate(): InputStream
    fun openIdentityData(): InputStream
}
