// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import android.view.Surface

/**
 * A module that can fill the motorcycle's screen.
 *
 * The app owns everything between the module and the dashboard: the compositor that draws the
 * overlays, the encoder, the T-Box transport, the foreground service that keeps it alive. What it
 * does not own is the picture. A module hands it one - decoded from wherever it likes - and takes
 * touches back.
 *
 * Deliberately not named after any one source. Android Auto was the first, and reading it as
 * "the Android Auto receiver" is what left the app full of Android Auto's vocabulary for things
 * that were never specific to it: a surface to draw into, a size, whether it can be touched.
 */

/** Touch actions as a projection source takes them. */
object ModuleTouchAction {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
}

/**
 * The video shapes a source can be asked for. The names are the contract: a source maps them onto
 * whatever its own protocol calls them, so a shape added here needs a mapping added there before
 * it is ever offered.
 */
enum class ModuleVideoPreset {
    LANDSCAPE_800X480,
    LANDSCAPE_1280X720,
    LANDSCAPE_1920X1080,
    LANDSCAPE_2560X1440,
    LANDSCAPE_3840X2160,
    PORTRAIT_720X1280,
    PORTRAIT_1080X1920,
    PORTRAIT_1440X2560,
    PORTRAIT_2160X3840
}

/**
 * What the app decided the picture should be, flattened to the numbers that reach a wire.
 *
 * The app keeps the reasoning - what the dashboard reported, which margins the rider taught, why
 * this shape was chosen - and hands over only the result. [touchWidth]/[touchHeight] are the part
 * of the video a rider can actually touch; the difference from the video is the letterbox, which
 * a source must not treat as picture. [sourceLabel] is for the log.
 */
data class ModuleVideoConfig(
    val preset: ModuleVideoPreset,
    val densityDpi: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val touchWidth: Int,
    val touchHeight: Int,
    val touchEnabled: Boolean,
    val sourceLabel: String
) {
    val marginWidth: Int get() = (videoWidth - touchWidth).coerceAtLeast(0)
    val marginHeight: Int get() = (videoHeight - touchHeight).coerceAtLeast(0)
}

/**
 * What one projection is built from.
 *
 * [videoSurface] is the compositor's input: the source draws there and the app composes its own
 * overlays on top, encodes, and sends. [mapTouchToSource] and [downstreamBlockedMillis] are the
 * two things a source needs back from that compositor, handed over as functions so the compositor
 * itself never crosses the boundary.
 *
 * [onEnded] reports (clean, userExit): clean when the far side closed things in order, userExit
 * when the rider chose to leave from the source's own interface.
 */
class ModuleProjectionSpec(
    val videoSurface: Surface,
    val video: ModuleVideoConfig,
    val initialNightMode: Boolean,
    val mapTouchToSource: (Int, Int) -> Pair<Int, Int>?,
    val downstreamBlockedMillis: (() -> Long)?,
    val onVideoReady: () -> Unit,
    val onEnded: (clean: Boolean, userExit: Boolean) -> Unit,
    val log: (String) -> Unit
)

/** One running projection. */
interface ModuleProjectionSource {
    /** False when it could not start; nothing is left running in that case. */
    fun start(): Boolean

    fun stop()

    /** Whether the far side is actually connected, as opposed to merely being listened for. */
    val isConnected: Boolean

    /** Whether anything ever connected during this projection. */
    val hasConnected: Boolean

    /** Coordinates in the output canvas; mapped through [ModuleProjectionSpec.mapTouchToSource]. */
    fun sendTouch(action: Int, pointerId: Int, canvasX: Int, canvasY: Int)

    fun sendTouch(action: Int, canvasX: Int, canvasY: Int) = sendTouch(action, 0, canvasX, canvasY)

    /** Coordinates already in the source's own space; forwarded without mapping. */
    fun sendSourceTouch(action: Int, pointerId: Int, sourceX: Int, sourceY: Int)

    fun sendSourceTouch(action: Int, sourceX: Int, sourceY: Int) =
        sendSourceTouch(action, 0, sourceX, sourceY)

    /** False when there is nothing running to tell. */
    fun setNightMode(isNight: Boolean): Boolean
}

/**
 * The capability a module offers when it can fill the motorcycle's screen.
 *
 * This is what the session service asks for: it never names a module's protocol, only what a
 * projection is. Whatever a source needs to identify itself - Android Auto needs a head-unit
 * certificate - it carries in its own package, which is what let this become the whole of the
 * conversation.
 */
interface ModuleProjection {
    fun createSource(host: MotoHubModuleHost, spec: ModuleProjectionSpec): ModuleProjectionSource

    /**
     * Ask the far side to connect, now that the source is listening.
     *
     * A source that only waits is useless for anything the rider has to be talked into: Android
     * Auto has to be woken through whichever entry point the installed release still exports,
     * and the app used to do that itself, in Android Auto's words, from three different places.
     * Whatever a module has to do here - dial something, poke an app, ask a rider to tap - it
     * does behind this call, and reports progress through [onProgress]. Returns when the far
     * side has connected or there is nothing left to try; a module that needs no waking returns
     * immediately.
     *
     * The detail reaching [onProgress] is what a rider reads while the screen says "ready" and
     * nothing has appeared yet, which is the wait most likely to be mistaken for a failure.
     */
    suspend fun awaken(
        host: MotoHubModuleHost,
        onProgress: (ModuleSessionDetail) -> Unit,
        log: (String) -> Unit
    )

    /**
     * Why nothing ever connected, in words for the rider.
     *
     * The host knows the source started and no picture arrived, and that is all it knows. Whether
     * the far side refused, accepted and ignored, or was never asked - and what to do about each -
     * is the module's to say. Deciding it in the host is what once told riders to hunt a video
     * problem while the far side had in fact refused to start at all.
     *
     * [connectedAtLeastOnce] is true when something connected earlier in this projection and then
     * stopped, which is a different story from never having connected.
     */
    fun explainNoConnection(host: MotoHubModuleHost, connectedAtLeastOnce: Boolean): String
}

/**
 * How far a [ModuleProjection] reaches, offered beside it through [MotoHubModule.capability].
 *
 * A module that offers none is a whole-screen projection, which is what every projection was
 * before contract 20: the app may run it as the motorcycle's whole session and calls it by name
 * wherever the projection is meant. A module that answers false here - a camera, say - only ever
 * fills a dashboard's panel: it gets a tile of its own among the dashboard's map sources and is
 * never taken for the session the rider starts from the mode page.
 *
 * A capability of its own rather than a member of [ModuleProjection], because the modules already
 * built implement that interface, and a member they never compiled would fail the moment the app
 * asked them.
 */
interface ModuleProjectionReach {
    val wholeScreen: Boolean
}

/**
 * How a projection module looks on its tile among the dashboard's map sources, offered beside
 * [ModuleProjection] through [MotoHubModule.capability].
 *
 * Without it the tile shows the app's generic picture of a projected screen - right for Android
 * Auto, wrong for a camera. Drawn by the module on a plain [android.graphics.Canvas], so it needs
 * nothing of the app's own drawing. Called on the main thread, every frame while the tile animates:
 * draw, do not load.
 */
interface ModuleProjectionTile {
    /** The colour the tile and the card are lit in while chosen, as ARGB. */
    val accent: Int

    /** One line under the tiles saying what the panel will show. */
    val caption: String

    /** The tile's picture, [width] by [height]. [phase] runs 0..1 and loops while the tile is chosen. */
    fun drawPicture(canvas: android.graphics.Canvas, width: Float, height: Float, accent: Int, phase: Float)

    /** The small mark beside the module's name, [size] square, in [color]. */
    fun drawMark(canvas: android.graphics.Canvas, size: Float, color: Int)
}
