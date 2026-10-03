// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.plugin

import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.Phase
import io.motohub.android.module.ModuleProjection
import io.motohub.android.module.ModuleProjectionReach
import io.motohub.android.module.ModuleProjectionSource
import io.motohub.android.module.ModuleProjectionSpec
import io.motohub.android.module.ModuleSessionDetail
import io.motohub.android.module.ModuleTouchAction
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The camera as a source for a dashboard's map panel.
 *
 * Panel only: the camera is not something to run the motorcycle's whole screen with, and saying so
 * keeps the app from taking it for its projection. A tap on the panel opens the module's own full
 * view, with every button; going back from there lands on the dashboard again.
 */
internal class DashcamProjection(private val controller: DashcamController) : ModuleProjection, ModuleProjectionReach {

    override val wholeScreen: Boolean get() = false

    override fun createSource(host: MotoHubModuleHost, spec: ModuleProjectionSpec): ModuleProjectionSource =
        PanelSource(host, controller, spec)

    /** Joining the camera's Wi-Fi is the waking: the panel waits on it, saying each step. */
    override suspend fun awaken(
        host: MotoHubModuleHost,
        onProgress: (ModuleSessionDetail) -> Unit,
        log: (String) -> Unit
    ) {
        controller.connect()
        var said = ""
        val deadline = System.currentTimeMillis() + AWAKEN_PATIENCE_MS
        while (System.currentTimeMillis() < deadline) {
            val state = controller.state.value
            if (state.connected || state.phase == Phase.FAILED) return
            if (state.message.isNotEmpty() && state.message != said) {
                said = state.message
                onProgress(ModuleSessionDetail(said))
            }
            delay(250)
        }
    }

    override fun explainNoConnection(host: MotoHubModuleHost, connectedAtLeastOnce: Boolean): String {
        if (controller.motorcycleConnected()) {
            return "The phone is on the motorcycle's Wi-Fi, and it has only one: the camera needs it. " +
                "Use the camera with the dashboard on the phone, without the motorcycle."
        }
        val state = controller.state.value
        if (state.phase == Phase.FAILED && state.message.isNotEmpty()) return state.message
        return if (connectedAtLeastOnce) {
            "The camera stopped answering. Is it still on, and close to the phone?"
        } else {
            "The camera did not answer. Check that it is on, and its Wi-Fi name and password in Modules > Dashcam."
        }
    }

    private companion object {
        /** A Wi-Fi join may wait up to a minute for the rider's approval; then the search. */
        const val AWAKEN_PATIENCE_MS = 75_000L
    }
}

/**
 * One panel showing the camera: a viewer of the shared live stream, drawing into the surface the
 * app composes the dashboard from.
 */
private class PanelSource(
    private val host: MotoHubModuleHost,
    private val controller: DashcamController,
    private val spec: ModuleProjectionSpec
) : ModuleProjectionSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var viewer: DashcamController.LiveViewer? = null
    private var watch: Job? = null

    @Volatile private var everConnected = false

    override val isConnected: Boolean get() = controller.state.value.connected

    override val hasConnected: Boolean get() = everConnected

    override fun start(): Boolean {
        if (viewer != null) return true
        controller.attach()
        val mine = controller.openLive()
        viewer = mine
        controller.setLiveSurface(mine, spec.videoSurface)
        spec.log("dashcam panel: ${spec.video.videoWidth}x${spec.video.videoHeight}")
        // Polled rather than collected: four checks a second is nothing, and it keeps to calls the
        // app is known to carry.
        watch = scope.launch {
            var shown = false
            var ended = false
            while (isActive) {
                val state = controller.state.value
                if (state.connected) {
                    everConnected = true
                    ended = false
                    controller.startLive()
                    if (!shown && mine.player.state.value.playing) {
                        shown = true
                        spec.onVideoReady()
                    }
                } else if (everConnected && !ended && !state.busy) {
                    // Gone after having been there: the host holds the panel open and says why.
                    ended = true
                    shown = false
                    spec.onEnded(false, false)
                }
                delay(250)
            }
        }
        return true
    }

    override fun stop() {
        watch?.cancel()
        watch = null
        viewer?.let { controller.closeLive(it) }
        viewer = null
        scope.cancel()
        controller.detach()
    }

    /** A finger lifted on the panel opens the full view; the panel has nothing else to press. */
    override fun sendTouch(action: Int, pointerId: Int, canvasX: Int, canvasY: Int) = touched(action)

    override fun sendSourceTouch(action: Int, pointerId: Int, sourceX: Int, sourceY: Int) = touched(action)

    private fun touched(action: Int) {
        if (action == ModuleTouchAction.UP) host.openFeature(DashcamModuleIds.LIVE)
    }

    override fun setNightMode(isNight: Boolean): Boolean = false
}
