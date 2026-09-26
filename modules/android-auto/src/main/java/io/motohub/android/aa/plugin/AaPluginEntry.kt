// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The module's front door: what the app loads by name and everything it can reach from there.
package io.motohub.android.aa.plugin

import io.motohub.android.aa.AaLog
import io.motohub.android.aa.AaNavigationGuidance
import io.motohub.android.aa.AaReceiver
import io.motohub.android.aa.AaSelfMode
import io.motohub.android.aa.AapPhoneHandshake
import io.motohub.android.aa.AndroidAutoSelfModeHelp
import io.motohub.android.aa.UsbAoaAccessoryConnection
import io.motohub.android.aaplugin.AaPluginContract
import io.motohub.android.module.ModuleAccessoryProbe
import io.motohub.android.module.ModuleAccessoryStreams
import io.motohub.android.module.ModuleGuidance
import io.motohub.android.module.ModuleGuidanceListener
import io.motohub.android.module.ModuleNavigation
import io.motohub.android.module.ModuleProbeOutcome
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleProjection
import io.motohub.android.module.ModuleProjectionSource
import io.motohub.android.module.ModuleProjectionSpec
import io.motohub.android.module.ModuleSessionDetail
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost

/**
 * The class the app names in the module manifest, built reflectively and the only one that is.
 *
 * Everything past here is ordinary code reached through the contract, so the module is written as
 * if it were compiled in - which is the point: the receiver does not know it is loaded, and the
 * app does not know which receiver answered.
 */
class AaPluginEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = AndroidAutoModule(host)
}

private class AndroidAutoModule(private val host: MotoHubModuleHost) : MotoHubModule {

    override val manifest = ModuleManifest(
        id = "android-auto",
        version = "0.1.5",
        contractVersion = 5,
        entryClass = AaPluginContract.ENTRY_CLASS,
        displayName = "Android Auto",
        description = "Runs Android Auto on the motorcycle's screen."
    )

    private val receiverCapability = AaModule()
    private val featureCapability = AaFeatures(host)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleProjection::class.java -> receiverCapability as T
        ModuleNavigation::class.java -> receiverCapability as T
        ModuleAccessoryProbe::class.java -> receiverCapability as T
        ModuleFeatures::class.java -> featureCapability as T
        else -> null
    }

    override fun release() = receiverCapability.release()
}

/**
 * Android Auto, offered only in the app's own words.
 *
 * Nothing here is an Android Auto interface any more. Filling the screen, waking the far side,
 * knowing where the rider is going, recognising a USB head unit - each is a capability any module
 * could offer, and each used to be a call the app made in Android Auto's vocabulary. What is
 * specific to Android Auto is now entirely on this side of the boundary: which app to poke, which
 * port to listen on, what its wire format means.
 */
private class AaModule : ModuleProjection, ModuleNavigation, ModuleAccessoryProbe {

    override fun createSource(host: MotoHubModuleHost, spec: ModuleProjectionSpec): ModuleProjectionSource =
        ModuleReceiver(host, spec)

    override fun explainNoConnection(host: MotoHubModuleHost, connectedAtLeastOnce: Boolean): String = when {
        connectedAtLeastOnce ->
            "Android Auto connected without delivering video. The session was closed; start " +
                "Android Auto again."
        AaSelfMode.anyEntryPointAccepted ->
            AndroidAutoSelfModeHelp.acceptedButSilentMessage(AaSelfMode.lastGearheadVersion)
        else -> AndroidAutoSelfModeHelp.NEVER_CONNECTED_MESSAGE
    }


    override suspend fun awaken(
        host: MotoHubModuleHost,
        onProgress: (ModuleSessionDetail) -> Unit,
        log: (String) -> Unit
    ) = AaSelfMode.trigger(
        context = host.context,
        dashboardNetwork = host.dashboardNetwork(),
        // The host used to pass this in, which meant the host had to know what "connected" means
        // for Android Auto. It never did know; it only forwarded an answer from here.
        isConnected = { AaReceiver.hasAndroidAutoConnectedSinceStart() },
        onProgress = onProgress,
        log = log
    )

    override fun setGuidanceListener(listener: ModuleGuidanceListener?) {
        if (listener == null) {
            AaNavigationGuidance.setListener(null)
            return
        }
        AaNavigationGuidance.setListener { snapshot -> listener.onGuidance(snapshot.toModule()) }
    }

    override fun probe(host: MotoHubModuleHost, streams: ModuleAccessoryStreams): ModuleProbeOutcome {
        val outcome = AapPhoneHandshake.run(
            context = host.context,
            // The probe presents the same head unit as a real session would, so it has to use
            // the same identity - anything else would be testing a peer we never actually are.
            identity = ModuleIdentity,
            connection = UsbAoaAccessoryConnection(streams),
            log = { host.log.log(it) }
        )
        return ModuleProbeOutcome(outcome.success, outcome.detail)
    }

    fun release() {
        AaNavigationGuidance.setListener(null)
        AaLog.sink = null
    }
}

private class ModuleReceiver(host: MotoHubModuleHost, spec: ModuleProjectionSpec) : ModuleProjectionSource {

    /**
     * The identity this module carries. */
    // No fallback to the app any more: the identity travels with this module, and a module
    // built without one cannot present a head unit at all - which AaReceiver.start reports.
    private val identity = ModuleIdentity

    private val receiver = AaReceiver(
        context = host.context,
        encoderSurface = spec.videoSurface,
        log = spec.log,
        onVideoReady = spec.onVideoReady,
        onSessionEnded = spec.onEnded,
        mapTouchToSource = spec.mapTouchToSource,
        capabilityProfile = spec.video,
        identity = identity,
        keySinks = host.keySinks,
        initialNightMode = spec.initialNightMode,
        downstreamBlockedMillis = spec.downstreamBlockedMillis
    )

    override fun start(): Boolean = receiver.start()

    override fun stop() = receiver.stop()

    override val isConnected: Boolean get() = receiver.hasLiveSession

    override val hasConnected: Boolean get() = receiver.hasAndroidAutoConnected

    override fun sendTouch(action: Int, pointerId: Int, canvasX: Int, canvasY: Int) =
        receiver.sendTouch(action, pointerId, canvasX, canvasY)

    override fun sendSourceTouch(action: Int, pointerId: Int, sourceX: Int, sourceY: Int) =
        receiver.sendSourceTouch(action, pointerId, sourceX, sourceY)

    override fun setNightMode(isNight: Boolean): Boolean = receiver.setNightMode(isNight)
}

private fun AaNavigationGuidance.Snapshot.toModule() = ModuleGuidance(
    active = active,
    rerouting = rerouting,
    maneuverType = maneuverType,
    roundaboutExitNumber = roundaboutExitNumber,
    road = road,
    distanceToManeuverMeters = distanceToManeuverMeters,
    timeToManeuverSeconds = timeToManeuverSeconds,
    distanceRemainingMeters = distanceRemainingMeters,
    timeToArrivalSeconds = timeToArrivalSeconds,
    estimatedTimeAtArrival = estimatedTimeAtArrival
)
