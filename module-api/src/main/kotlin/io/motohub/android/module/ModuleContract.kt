// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import android.content.Context
import java.io.File

/**
 * The contract every ADV-SOLO module is loaded against.
 *
 * A module is a file the app fetches and loads at runtime, in this process, through a class
 * loader whose parent is the app's own - so everything in this package resolves to the app's
 * copy on both sides. Android Auto is the first one; nothing here knows that. A module offers
 * [capabilities][MotoHubModule.capability], the app asks for the one it wants by type, and the
 * two only ever meet through an interface the app already ships.
 *
 * Rule of the road: **append, never insert or rename.** An older module answering a newer app
 * must fail on the capability it lacks, not misbehave on one it misreads.
 * See docs/contract.md in the MOTO-HUB module SDK.
 */
object MotoHubModuleContract {
    /**
     * Bump whenever anything in this package changes shape.
     *
     * 1: the initial contract.
     * 2: projection moved here from the Android Auto contract and was renamed to say what it is
     *    (ModuleProjectionSource/Spec/VideoConfig); the host gained a shared session runtime.
     *    Not an append - the shapes changed - so 1 is no longer loadable.
     * 3: the last three things the app still asked a module in Android Auto's words became
     *    ordinary capabilities: waking the far side (ModuleProjection.awaken), turn-by-turn
     *    guidance (ModuleNavigation) and the USB accessory probe (ModuleAccessoryProbe). With
     *    those moved, the app no longer ships an Android-Auto-shaped contract at all - so a
     *    module built against 2 has nothing left to talk to.
     * 4: the host gained openFeature, so a module's screen can reach another of its own. An
     *    append, and on an interface the app implements rather than the module - a module built
     *    against 3 never calls it and keeps working, which is why the minimum stayed at 3.
     * 5: the host gained ui, lending a module the app's own screen vocabulary, and features
     *    gained the MODULES placement - a page about the module, where a rider goes looking for
     *    it. Both appends, both on the app's side of the boundary.
     * 6: a module can hand over the sound of what it projects (ModuleAudio). A new capability,
     *    which an older module simply does not answer - so the minimum stays at 3.
     * 7: the other direction. A module can put the app's own Ride Dashboard on somebody else's
     *    head unit (ModuleNavigator), and to do it the host lends it the dashboard itself
     *    (MotoHubModuleHost.dashboard) and where the rider is going (MotoHubModuleHost.guidance).
     *    The capability is the module's and an older one does not answer it; both host members
     *    are appends on the app's side of the boundary - so, again, the minimum stays at 3.
     * 8: ModuleDashboardHost.setAutoDrive, so a head unit's "drive this route for me" reaches the
     *    only side that has positions to move. An append on the app's side again; the minimum
     *    stays at 3, and a module built against 7 simply never asks.
     * 9: the three things a head unit could ask for and the app had no way to answer - ending the
     *    ride it started (stopNavigating), a destination named rather than located
     *    (navigateToQuery), and which unit the rider reads distances in (distanceUnits). All
     *    appends on the app's side; the minimum stays at 3.
     * 10: ModuleAccessoryBridge, for a module that can sit between a real head unit and Android
     *    Auto and read what passes. The capability is the module's and an older one does not
     *    answer it, so the minimum stays at 3.
     * 11: the host lends the rider's rides and routes (MotoHubModuleHost.rides) and a 3D terrain
     *    scene a module can direct (MotoHubModuleHost.scene) - for a module that turns a ride into
     *    a film. Both appends on the app's side; the minimum stays at 3.
     * 12: ModuleScene.setLook (map style, relief, haze, how the track is drawn),
     *    ModuleScene.setMarkers (labelled pins on the terrain), ModuleRideLibrary.engineRpm
     *    (the OBD engine speed along a ride) and ModuleUi.Backdrop (the app's page ground for a
     *    module's own full-screen layout). Appends to interfaces the app implements; the minimum
     *    stays at 3.
     * 13: ModuleScene.export, rendering a scene's film to a video frame by frame with a module's
     *    overlay drawn over it. An append on the app's side; the minimum stays at 3.
     * 14: features can be actions on one ride or route - the RIDE_ACTION and ROUTE_ACTION
     *    placements, drawn on a recorded trip's page and on a route's preview - and the host says
     *    which one a feature was opened on (MotoHubModuleHost.openedFor). A route only previewed,
     *    neither saved nor being navigated, is ModuleRideEntry.KIND_PREVIEW_ROUTE. A scene can
     *    film through a 360° or little-planet lens (ModuleScene.setLens), be drawn by ArcGIS with its
     *    weather (ModuleScene.setEngine, setWeather), blend a second camera for dissolves
     *    (ModuleScene.setBlend), a feature can be a button at the top of Trips (the TRIPS_HEADER
     *    placement), and a module can ask the rider's own language model (MotoHubModuleHost.ai).
     *    All appends; the minimum stays at 3.
     * 15: ModuleExportSpec.draft, a lighter 3D picture for checking a film before the real export
     *    (ArcGIS: medium quality, no shadows, reflections or buildings), and realTime, captureSpeed,
     *    warmUp and hevc: a film recorded as it plays instead of frame by frame, and H.265. Appends
     *    with defaults, so a module built against 14 still constructs its spec; the minimum stays at 3.
     * 16: ModuleScene.setCredits, credit lines a module owes for what it put in a film (its music,
     *    its photos), written into the credit roll that already names the maps. An append; the
     *    minimum stays at 3.
     * 17: ModuleScene.setActionPath and ModuleSceneAction, the rider's figure playing little
     *    scenes along the film (a thumb up, a wave, a wheelie, a stop for a selfie); and the
     *    models of real motorcycles among the ModuleSceneRider figures. An append; the minimum
     *    stays at 3.
     * 18: ModuleSceneEngine.ULTRA, a third engine for ModuleScene.setEngine: MOTO-HUB's own
     *    game-grade world - the road as real road, forests, sky and weather drawn around it. A new
     *    value for an existing call; an older app clamps it to an engine it has, so the minimum
     *    stays at 3.
     * 19: ModuleRenderSettings and ModuleExportSpec.render, segments and preview: the Real 3D
     *    engine's export finish (TAA or SMAA, motion blur, depth of field, volumetric clouds,
     *    water), a render of only a few stretches of the film kept as a throw-away preview,
     *    ModuleScene.setDiagnostics and ModuleScene.setBasicMode (the live scene drawn without any
     *    effect, for a phone that stutters). Appends with defaults; the minimum stays at 3.
     * 20: ModuleProjectionReach, a projection that only fills a dashboard's panel (a camera)
     *    rather than the whole screen. Every installed projection module gets its own tile among
     *    the dashboard's map sources, and ModuleProjectionTile lets a module draw that tile itself
     *    (picture, mark, colour, caption). New capabilities, asked for and absent in older modules;
     *    the minimum stays at 3.
     * 21: ModuleRenderSettings.realistic, the Real 3D engine's highest tier for a film (ULTRA-REALISTIC).
     *    Appended with a default; the minimum stays at 3.
     * 22: ModuleRideWriter (a module saves simulated rides and routes), ModuleRouting, ModulePlaces,
     *    ModuleSights and ModuleMapHost (the NAV's routing, speed limits, elevation, place search
     *    (and the rider's last known position), the viewpoints, passes and landmarks near a road, and a flat map lent to a module), ModuleBridge and ModuleExtensions (a module opens another's feature
     *    on a ride, and adds an entry to another module), ModuleRideLibrary.isSimulated. New host
     *    members and a new capability; the minimum stays at 3.
     * 23: remote rendering. A film can be drawn on another computer on the rider's network: a module
     *    lists those computers (ModuleScene.renderServers, watchRenderServers, pairRenderServer),
     *    hands over its overlay as data (ModuleRemoteRender, ModuleExportSpec.withRemoteRender) and
     *    says where the film should be drawn (ModuleExportSpec.renderOn); ModuleExportJob.renderedOn
     *    says where it is. Appends with defaults, and none of them in an existing constructor; the
     *    minimum stays at 3.
     * 24: the look of a valley after the rain. ModuleSceneWeather.AFTER_RAIN, and ModuleRenderSettings
     *    gains the Real 3D export's wet road and its reflections, valley mist and low cloud, season,
     *    dense grass and flowers, fences, waterfalls and a colour grade. Appended with defaults that
     *    draw today's picture; the minimum stays at 3.
     * 25: spread rendering. A film can be cut into pieces drawn at the same time by several devices
     *    and put together on the phone: ModuleExportSpec.RENDER_SPREAD asks for it (RENDER_AUTO
     *    still means one device), ModuleScene.spreadDevices lists the devices it would use,
     *    ModuleRenderServer.kind and .spreads say what each device is and whether it can draw a
     *    piece, ModuleExportJob.renderedOnAll and spreadProgress say who draws and how far each one
     *    is (ModuleSpreadDevice), and renderedOn is null for such a film. Appends with defaults, none
     *    in an existing constructor; the minimum stays at 3.
     * 26: a project edited on a computer. ModuleSceneHost.remoteEditing lists the paired computers
     *    that open a module's projects (ModuleRemoteEditing.editors, watchEditors) and sends one there
     *    (send: ModuleRemoteEdit, followed through ModuleRemoteEditState, stopped or left with
     *    ModuleRemoteEditJob). A default method and new types only; the minimum stays at 3.
     */
    const val CONTRACT_VERSION = 26

    /**
     * The oldest contract this app can still run.
     *
     * Without it a module built against an older, incompatible shape loads happily and then
     * throws AbstractMethodError on the first call - which is what a stale module looked like
     * before this existed, and reads to a rider as the app being broken rather than the module
     * being old. Raised only when a change is genuinely not backwards compatible; appended calls
     * do not need it.
     */
    const val MINIMUM_CONTRACT_VERSION = 3

    /**
     * Where a module keeps its own description, inside the module file itself.
     *
     * A plain entry in the zip rather than something only code can answer: the app has to be
     * able to say what a file claims to be - and refuse it - before it loads a single class
     * out of it.
     */
    const val MANIFEST_ENTRY = "META-INF/motohub-module.json"
}

/**
 * What a module says about itself, read out of [MotoHubModuleContract.MANIFEST_ENTRY] before any
 * of its code runs.
 *
 * [id] is the stable name the app installs and looks the module up by ("android-auto"), and it
 * is what a file is filed under on disk - so it may only contain characters that are safe in a
 * directory name. [version] is the module's own, and is what an update compares.
 * [contractVersion] is the [MotoHubModuleContract.CONTRACT_VERSION] it was built against.
 */
data class ModuleManifest(
    val id: String,
    val version: String,
    val contractVersion: Int,
    val entryClass: String,
    val displayName: String,
    val description: String
) {
    val isIdSafe: Boolean get() = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}

/** Where a module's log lines go: the app's own event log, so a diagnostics report stays one log. */
fun interface ModuleLogSink {
    fun log(line: String)
}

/**
 * Keys and scroll from the phone's own controls into whatever is running.
 *
 * A module installs one of these while its projection is up and clears it when the projection
 * ends; the app keeps the registry and decides who is allowed to press. Generic because it always
 * was: a handlebar button and a phone control do not care what is on the screen.
 */
interface ModuleKeySink {
    fun sendKey(keycode: Int)
    fun sendScroll(delta: Int)
}

interface ModuleKeySinkRegistry {
    fun install(sink: ModuleKeySink)

    /** Clears [sink] if it is the installed one, or whatever is installed when [sink] is null. */
    fun clear(sink: ModuleKeySink?)
}

/**
 * The motorcycle's own access point, when a session is up.
 *
 * A projection may need to describe the network the dashboard is on - Android Auto stores it
 * against the car it is asked to remember - and only the app knows it. Null when nothing is
 * connected or the credentials are not known, which is an ordinary answer: without it there is
 * nothing truthful to say, and a module should say nothing rather than guess.
 */
class ModuleDashboardNetwork(val ssid: String, val password: String)

/**
 * What the app lends every module, whatever it does.
 *
 * [storage] is a directory of the module's own, which survives an app update and is removed when
 * the module is uninstalled. A module writes nothing anywhere else: it has no idea where the app
 * keeps its own files, and should not.
 */
interface MotoHubModuleHost {
    val context: Context
    val storage: File
    val log: ModuleLogSink

    /**
     * The session this module shares with the app: the module drives it, the app keeps it alive
     * and reports it. See [ModuleSessionRuntime].
     */
    val session: ModuleSessionRuntime

    /** Where a running projection publishes its key/scroll sender. */
    val keySinks: ModuleKeySinkRegistry

    /** The dashboard's network, or null when there is nothing truthful to report. */
    fun dashboardNetwork(): ModuleDashboardNetwork?

    /**
     * Opens another of this module's own features, by the id it filed the feature under.
     *
     * This is what makes [ModuleFeaturePlacement.NONE] mean something: a feature the app shows
     * nowhere, reached from a screen the module already has open. Without it a module could only
     * ever offer as many screens as it had places to be listed.
     *
     * Scoped to the module this host belongs to, deliberately - a module addresses its own
     * screens and cannot summon another module's. An unknown id does nothing rather than
     * throwing: the feature list is the module's own, and a typo should not take the app down.
     */
    fun openFeature(featureId: String)

    /**
     * The app's screen vocabulary, so a module's pages look like the app's. See [ModuleUi].
     *
     * A module is free to ignore it and draw with plain Compose; what it cannot do is reach the
     * app's own components, which is why this exists at all.
     */
    val ui: ModuleUi

    /**
     * The app's own Ride Dashboard, for a module that has somebody else's screen to fill.
     *
     * The mirror image of [ModuleProjection]: there the module supplies the picture and the app
     * owns the screen; here the app supplies the picture and the module owns the screen. See
     * [ModuleDashboardHost].
     */
    val dashboard: ModuleDashboardHost

    /** Where the rider is going, for a module whose host wants to draw its own turn card. */
    val guidance: ModuleGuidanceSource

    /** The rider's recorded rides and saved routes, read-only. See [ModuleRideLibrary]. */
    val rides: ModuleRideLibrary

    /** The app's 3D terrain scene, for a module that directs a camera over a track. See [ModuleSceneHost]. */
    val scene: ModuleSceneHost

    /**
     * The ride or route a [ModuleFeaturePlacement.RIDE_ACTION] or
     * [ModuleFeaturePlacement.ROUTE_ACTION] feature was opened on, handed to [rides] as it is;
     * null for a feature opened anywhere else.
     */
    fun openedFor(): ModuleRideEntry?

    /** The language model the rider set up in the app's AI settings, if any. See [ModuleAi]. */
    val ai: ModuleAi

    /** Saves simulated rides and routes into TRIPS and the NAV (contract 22). See [ModuleRideWriter]. */
    val rideWriter: ModuleRideWriter

    /** The NAV's routing, speed limits and elevation (contract 22). See [ModuleRouting]. */
    val routing: ModuleRouting

    /** The NAV's place search (contract 22). See [ModulePlaces]. */
    val places: ModulePlaces

    /** The viewpoints, passes and landmarks near a road, from OpenStreetMap (contract 22). See [ModuleSights]. */
    val sights: ModuleSights

    /** A flat map lent by the app (contract 22). See [ModuleMapHost]. */
    val maps: ModuleMapHost

    /** Other modules: whether they are installed, opening their features, their extensions (contract 22). See [ModuleBridge]. */
    val modules: ModuleBridge
}

/**
 * The rider's own language model, as the app's AI settings configure it (an OpenAI-compatible
 * endpoint with the rider's key). The key never reaches the module: it asks, the app sends.
 */
interface ModuleAi {
    /** Whether a key and a model are set, so [complete] can be tried at all. */
    fun isReady(): Boolean

    /**
     * Sends [system] and [user] and returns the model's text, or throws with why not. Blocking:
     * call it off the main thread. [json] asks for a JSON object where the server honours it.
     */
    fun complete(system: String, user: String, maxTokens: Int, json: Boolean): String
}

/**
 * A loaded module.
 *
 * [capability] is the whole of how the app reaches what a module does: it asks for an interface
 * it ships itself, and gets an implementation or null. Null is an ordinary answer - a module
 * built before that capability existed simply does not offer it, and the caller degrades instead
 * of failing.
 */
interface MotoHubModule {
    val manifest: ModuleManifest

    fun <T : Any> capability(type: Class<T>): T?

    /** Drops everything the module holds. The app calls it before unloading or replacing it. */
    fun release()
}

/**
 * Implemented by the class the manifest names in [ModuleManifest.entryClass]. Must have a public
 * no-argument constructor: it is built reflectively, and it is the only class in a module that is.
 */
interface MotoHubModuleEntry {
    fun create(host: MotoHubModuleHost): MotoHubModule
}

/** Convenience for the common `capability(Foo::class.java)` call. */
inline fun <reified T : Any> MotoHubModule.capability(): T? = capability(T::class.java)
