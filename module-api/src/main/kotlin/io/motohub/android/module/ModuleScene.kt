// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see module-api/LICENSE.
package io.motohub.android.module

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget

/**
 * A 3D terrain scene the app draws and a module directs.
 *
 * The app already carries a map engine with real relief and satellite imagery; a module could not
 * ship a second one, and could not reach the first - it lives in the app's assets behind a bridge
 * R8 renames. So the app lends the scene and the module supplies the direction: a track to lay on
 * the ground and a shot, which is where the camera is and what it looks at on every frame.
 *
 * The module computes the whole shot up front and the scene plays it back itself. Steering the
 * camera one call per frame across the bridge would stutter whenever the bridge did; a shot
 * handed over once plays as smoothly as the map can draw, and seeking is just a frame number.
 */
interface ModuleSceneHost {

    /** A new scene. The caller owns it and must [close][ModuleScene.close] it. */
    fun open(): ModuleScene
}

/**
 * One scene. Every call may be made before the map has loaded: the scene keeps the latest of each
 * and applies them when it is ready, which it reports through [ModuleSceneListener.onSceneReady].
 */
interface ModuleScene {

    /** The scene itself, filling whatever space its parent gives it. */
    @Composable
    @ComposableTarget(ModuleUi.UI_APPLIER)
    fun Surface()

    fun setListener(listener: ModuleSceneListener?)

    /** The line to lay on the terrain, with one ARGB colour per point. */
    fun setTrack(latitudes: DoubleArray, longitudes: DoubleArray, colors: IntArray)

    /** Replaces the shot. Playback keeps its frame number, clamped to the new length. */
    fun setShot(shot: ModuleSceneShot)

    fun play()

    fun pause()

    fun seek(frame: Int)

    /** Frees the map and everything it loaded. The scene cannot be used afterwards. */
    fun close()

    /** How the scene looks, apart from the camera (contract 12). See [ModuleSceneLook]. */
    fun setLook(look: ModuleSceneLook)

    /** Labelled pins standing on the terrain, replacing any given before (contract 12). */
    fun setMarkers(markers: ModuleSceneMarkers)

    /**
     * Renders the film to a video in the rider's gallery (contract 13).
     *
     * Frame by frame, not in real time: each frame is drawn off screen at the video's size, the
     * scene waits until its terrain and map are loaded, [overlay] is drawn over it for that frame
     * - the shot's frame number, fractional frames rounded - and only then is it encoded, with
     * the exact time it belongs at. So a slow phone makes a slow export, never a stuttering film.
     *
     * The scene's current track, shot, look and markers are what is rendered. Callbacks arrive on
     * the main thread; the returned job cancels it.
     */
    fun export(
        spec: ModuleExportSpec,
        overlay: @Composable @ComposableTarget(ModuleUi.UI_APPLIER) (frame: Int) -> Unit,
        listener: ModuleExportListener
    ): ModuleExportJob

    /**
     * The lens the scene films through (contract 14), one of [ModuleSceneLens]: an ordinary one,
     * a whole sphere around the camera laid out 2:1 (equirectangular, with the metadata that makes
     * players and headsets show it as a 360° video), or that sphere folded down into a little
     * planet. The last two draw each frame several times over, so they play and export slower.
     */
    fun setLens(lens: Int)

    /**
     * Which 3D engine draws the scene (contract 14), one of [ModuleSceneEngine]: the app's own
     * map engine, ArcGIS - real sunlight with shadows, atmosphere, weather, water - with the
     * rider's Esri key when there is one, or from contract 18 the Real 3D engine (ULTRA), MOTO-HUB's own
     * game-grade world built around the road. Switching reloads the scene; the film is kept. A
     * value this app does not know draws with the nearest one it does.
     */
    fun setEngine(engine: Int)

    /**
     * The sky over an ArcGIS scene (contract 14), one of [ModuleSceneWeather], with [cloudCover]
     * 0..1 how much of it there is: the cloud of a sunny or cloudy sky, how hard it rains or
     * snows, how thick the fog. Clouds stand in the scene, between the camera and the ground, not
     * only overhead. The app's own engine has no weather and ignores it.
     */
    fun setWeather(weather: Int, cloudCover: Float)

    /**
     * Weather that changes along the film (contract 14): one [ModuleSceneWeather] and one amount
     * 0..1 per frame of the shot, as [setWeather] takes them, the sky moving from one to the next.
     * Where it is given it wins over [setWeather]; null, or arrays that do not fit the shot, for
     * one weather all along.
     */
    fun setWeatherPath(weather: IntArray?, cloudCover: FloatArray?)

    /** What the scene adds to the land (contract 14); see [ModuleSceneExtras]. */
    fun setExtras(extras: ModuleSceneExtras)

    /**
     * Credits the film owes besides the maps' own (contract 16), for the music or the photos in
     * it: [title] heads the block ("MUSIC") and [lines] are its entries, one short line each.
     * The scene writes them into the same credit roll that names the maps, the imagery and the
     * relief in the film's last seconds, on screen and in an export, so the film carries all of
     * its credits in one place. An empty list takes the block away.
     */
    fun setCredits(title: String, lines: List<String>)

    /**
     * A second camera blended over the shot (contract 14), for a dissolve from one clip into the
     * next: [second] has the shot's frame count, [mix] how much of it shows at each frame, 0 for
     * none to 1 for all of it. The app's own engine draws both pictures and blends them; ArcGIS,
     * which cannot, dips through black between them. Null for none.
     */
    fun setBlend(second: ModuleSceneShot?, mix: FloatArray?)

    /**
     * What the rider's figure does along the film (contract 17): per frame of the shot, the action
     * it plays ([codes], 0 for none - see [ModuleSceneAction]), how many [seconds] into it and how
     * long it lasts ([lengths], seconds), and [flags] ([ModuleSceneAction.RIGHT]: the right hand or
     * foot plays it, else the left; [ModuleSceneAction.STOPPED]: the bike stands still). Drawn by
     * the ArcGIS engine on its motorcycles; the app's own engine shows a sign over the dot. Null,
     * or arrays that do not fit the shot, for none.
     */
    fun setActionPath(codes: IntArray?, seconds: FloatArray?, lengths: FloatArray?, flags: IntArray?)

    /**
     * A small performance read-out over the live scene (contract 19): frame rate, draw calls,
     * triangles and the graphics memory each part of the world holds. Real 3D only; never part of
     * an export. Off by default, and while it is on the scene writes at most one summary line to
     * the log every half minute - it never logs per frame.
     */
    fun setDiagnostics(overlay: Boolean) {}

    /**
     * A fast preview (contract 19): the real 3D only, for the live scene and the preview, never an
     * export. The Real 3D engine then draws just the relief with its photo imagery (and the road,
     * the buildings and the rider's geometry), with no post-processing, shadows, lights,
     * atmosphere, clouds, weather or other effect, on a lighter world (fewer and nearer trees,
     * buildings and road, no minor roads, hedges or ground cover; built again when this changes)
     * - for a phone that cannot keep the film smooth. Off by default; any other engine ignores it.
     */
    fun setBasicMode(basic: Boolean) {}

    /**
     * The computers the rider has set up to draw films (contract 23), as the app knows them now:
     * the ones paired with this phone, found or not, and the ones found on the network that are not
     * paired yet. Empty when there is none, and on an app that does not do this. The status of each
     * is refreshed in the background while somebody is watching ([watchRenderServers]); this is the
     * latest known, not a new round of questions.
     */
    fun renderServers(): List<ModuleRenderServer> = emptyList()

    /**
     * Calls [listener] now and whenever the list of [renderServers] or the state of one of them changes (contract 23),
     * on the main thread, and keeps the app asking those computers how they are (every few seconds) until the
     * returned handle is closed. Close it when the screen that shows the list goes away.
     */
    fun watchRenderServers(listener: (List<ModuleRenderServer>) -> Unit): AutoCloseable {
        listener(emptyList())
        return AutoCloseable { }
    }

    /**
     * Opens the app's own screen for pairing a computer with this phone (contract 23): the code Studio shows, typed or read from
     * its QR. The app owns it, the module only asks for it; [renderServers] changes when it is done.
     */
    fun pairRenderServer() {}
}

/**
 * A computer that can draw a module's film for it (contract 23): MOTO-HUB Studio with its render server switched on.
 *
 * [id] is stable and is what [ModuleExportSpec.renderOn] names. [paired]: this phone may use it; a computer that is found but
 * not paired is listed so that the module can offer to pair it ([ModuleScene.pairRenderServer]). [ready]: it is answering and
 * can draw now - false when it is not found on this network ([problem] [PROBLEM_OFFLINE]) or lacks something it needs.
 * [busy]: it is drawing another film; [queue] how many wait behind that one - a film sent now waits too. [overlays]: the remote
 * overlays it can draw, by [ModuleRemoteRender.overlayKind] and the versions of each one's format it reads; a module whose
 * overlay is not in it has to say the computer needs updating rather than send it a film it cannot draw.
 */
class ModuleRenderServer @JvmOverloads constructor(
    val id: String,
    val name: String,
    val paired: Boolean,
    val ready: Boolean,
    val busy: Boolean,
    /** One of the PROBLEM_ constants, or null. */
    val problem: String?,
    val queue: Int = 0,
    val overlays: Map<String, List<Int>> = emptyMap()
) {
    /** Whether this server draws [kind] at [version] of its format. */
    fun draws(kind: String, version: Int): Boolean = overlays[kind]?.contains(version) == true

    companion object {
        /** A paired computer that is not answering on this network now. */
        const val PROBLEM_OFFLINE = "OFFLINE"
        const val PROBLEM_BROWSER_MISSING = "BROWSER_MISSING"
        const val PROBLEM_FFMPEG_MISSING = "FFMPEG_MISSING"

        /** It draws, but on the processor, not a graphics card: much slower. */
        const val PROBLEM_SOFTWARE_GL = "SOFTWARE_GL"

        /** The phone's pairing with it is no longer good (revoked, or the computer was reset): pair again. */
        const val PROBLEM_NEEDS_PAIRING = "NEEDS_PAIRING"
    }
}

/**
 * What a module hands over so that its film can be drawn on another computer (contract 23): the overlay as data, because code
 * cannot travel. The computer draws the picture from the same sources as the phone and the overlay from its own copy of the
 * module's drawing code, reading [overlayJson] in the format [overlayKind] at [overlayVersion].
 *
 * [files] are what the overlay refers to (pictures), by the relative path the overlay's data names them under, e.g.
 * `photos/p1.jpg`. [lens360] marks the film as a whole sphere, for the phone to put in the video's metadata once it is back.
 * [overlayHash] is a fingerprint of the source of the module's drawing code that this film was written for: a computer whose own
 * copy has another one says so to the rider ("the overlay is out of date") and draws the film all the same. Empty when the
 * module does not keep one.
 */
class ModuleRemoteRender @JvmOverloads constructor(
    val overlayKind: String,
    val overlayVersion: Int,
    val overlayJson: String,
    val files: Map<String, java.io.File> = emptyMap(),
    val lens360: Boolean = false,
    val overlayHash: String = ""
)

/** The actions of [ModuleScene.setActionPath]: a code per scene, and the flags. */
object ModuleSceneAction {
    const val NONE = 0
    const val THUMB_UP = 1
    const val BIKER_WAVE = 2
    const val WAVE = 3
    const val WHEELIE = 4
    const val FOOT_THANKS = 5
    const val FIST_PUMP = 6
    const val ROCK_ON = 7
    const val POINT_OUT = 8
    const val LOOK_AROUND = 9
    const val VISOR_UP = 10
    const val HELMET_SALUTE = 11
    const val SELFIE = 20
    const val STRETCH = 21
    const val COFFEE = 22
    const val FINAL_WAVE = 23
    const val READY_GO = 24

    const val RIGHT = 1
    const val STOPPED = 2
}

/**
 * What to render. [width] and [height] are the video's pixels (even numbers); [framesPerSecond]
 * may differ from the shot's - the shot is sampled between its frames. [audioUri] is a sound
 * file the rider picked (a content Uri) or null for a silent film; it starts [audioStartMillis]
 * into the song, at [audioVolume] (0..1), and fades out over the film's last seconds. From
 * contract 14 a negative [audioStartMillis] places the song that much later than the film's
 * start, with silence before it.
 * [fileName] is the name the video gets in Movies/MOTO-HUB; [parallelRenderers] is how many
 * frames are drawn at the same time.
 */
class ModuleExportSpec @JvmOverloads constructor(
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val bitRate: Int,
    val audioUri: String?,
    val audioStartMillis: Long,
    val audioVolume: Float,
    val fileName: String,
    /**
     * How many frames are drawn at once, each by its own off-screen scene. Waiting for map tiles
     * and relief is most of a frame's time, and it waits just as well in parallel; the app may use
     * fewer if the phone cannot start them all.
     */
    val parallelRenderers: Int,
    /**
     * A lighter 3D picture (contract 15), for checking a film before the real export: the ArcGIS
     * engine draws at medium quality with no shadows, reflections or buildings, and each frame
     * loads a good deal less. The map engine ignores it.
     */
    val draft: Boolean = false,
    /**
     * Filmed as it plays (contract 15): the film runs once on an off-screen display and is
     * recorded as it goes, instead of waiting for every frame's map and relief. Minutes instead of
     * the better part of an hour, but what the scene had not loaded yet, or a frame the phone did
     * not manage to draw, is in the video as it was. [parallelRenderers] is ignored.
     */
    val realTime: Boolean = false,
    /**
     * [realTime] only: the share of its own pace the film plays at while it is filmed, 0.05..1.
     * Half speed gives the scene twice the time for each frame, and the video is still the film's
     * length: the recording is put back to its pace.
     */
    val captureSpeed: Float = 1f,
    /**
     * [realTime] only: the film is played once without filming first, so the map and the relief
     * it needs are already in the cache when it is filmed. Twice the time.
     */
    val warmUp: Boolean = false,
    /**
     * H.265 instead of H.264 (contract 15): about half the file for the same picture. The app
     * falls back to H.264 at a higher [bitRate] on a phone that cannot encode this size in H.265.
     */
    val hevc: Boolean = false,
    /**
     * How the Real 3D engine renders each frame (contract 19): anti-aliasing, motion blur, depth of
     * field, volumetric clouds, water. Null for the engine's plain look. Applies to a frame by
     * frame export only: a [realTime] film, the live scene and the other engines ignore it, and so
     * does a 360° or little-planet lens.
     */
    val render: ModuleRenderSettings? = null,
    /**
     * Only some stretches of the film (contract 19), as flat pairs of start frame and frame count in
     * the video's own frames (at [framesPerSecond]): `[0, 75, 600, 75]` is the first 75 frames and
     * 75 more from frame 600. The video is those stretches one after the other. Null for the whole
     * film. Together with [preview] this is how a module shows what a render will look like before
     * paying for all of it. Each pair is clipped into the film, empty ones are dropped, the order
     * given is kept and the stretches add up to at most 2400 frames; progress counts those frames.
     * Pairs that all fall outside the film fail the export. The song, when there is one, plays from
     * the start of the result, not of the film.
     */
    val segments: IntArray? = null,
    /**
     * A throw-away render (contract 19): the video is written to the app's cache, not the gallery -
     * [ModuleExportListener.onExportFinished] gets a file: Uri that is valid until the app is next
     * started or the next preview - and it has no sound, no notification of its own beyond the
     * export's progress, and no signature outro. It is always drawn frame by frame, whatever
     * [realTime] says, and always encoded as H.264. The overlay is drawn on every frame as in a
     * real export. A new preview or an export replaces the file.
     */
    val preview: Boolean = false
) {
    /**
     * Where the film is drawn, when not on the phone (contract 23): what the module gives a render server to draw its overlay
     * with. Null: no remote drawing is possible for this film, and it is drawn on the phone. Set with [withRemoteRender]; read by
     * the app.
     *
     * Not constructor parameters on purpose: a module built before 23 calls the constructor, and Kotlin compiles a call that
     * leaves arguments out to a hidden constructor whose shape lists every parameter - adding one would break every such module
     * on this app. A copy that carries these is the append that costs nobody anything.
     */
    var remote: ModuleRemoteRender? = null
        private set

    /**
     * Where the module wants the film drawn (contract 23): [RENDER_AUTO] (the default: a paired computer that is ready and not busy,
     * otherwise the phone), [RENDER_PHONE], or the [ModuleRenderServer.id] of one computer. A named computer that is not paired, not
     * answering or busy is not replaced by another, silently: the export fails with a message and the module offers the phone.
     */
    var renderOn: String = RENDER_AUTO
        private set

    /**
     * A copy of this spec that asks to be drawn remotely (contract 23): [remote] for the overlay, [renderOn] for where.
     * A render server always draws frame by frame, so [realTime], [captureSpeed], [warmUp] and [parallelRenderers] are
     * ignored there; [preview] and [segments] are always drawn on the phone.
     */
    @JvmOverloads
    fun withRemoteRender(remote: ModuleRemoteRender?, renderOn: String = RENDER_AUTO): ModuleExportSpec =
        ModuleExportSpec(
            width, height, framesPerSecond, bitRate, audioUri, audioStartMillis, audioVolume, fileName, parallelRenderers,
            draft, realTime, captureSpeed, warmUp, hevc, render, segments, preview
        ).also {
            it.remote = remote
            it.renderOn = renderOn
        }

    companion object {
        /** [renderOn]: the app chooses - a ready, idle, paired computer if there is one, otherwise the phone. */
        const val RENDER_AUTO = "auto"

        /** [renderOn]: draw it on the phone. */
        const val RENDER_PHONE = "phone"
    }
}

/**
 * The finish of a Real 3D film (contract 19), the render options of [ModuleExportSpec.render].
 * Every option costs time, and a few cost graphics memory; all are off or plain by default.
 */
class ModuleRenderSettings @JvmOverloads constructor(
    /** [AA_SMAA]: the fast edge smoothing the engine always had; [AA_TAA]: [taaSamples] jittered draws of the frame averaged. */
    val antiAlias: Int = AA_SMAA,
    /** [AA_TAA] only: 4, 8 or 16 draws per frame. */
    val taaSamples: Int = 8,
    val motionBlur: Boolean = false,
    /** The shutter's open angle in degrees, 0..360: 180 is the cinema standard, half the frame time. */
    val shutterDegrees: Float = 180f,
    /** 0 light, 1 balanced, 2 smooth: how many taps along the motion. */
    val motionBlurQuality: Int = 1,
    val depthOfField: Boolean = false,
    /** 0..1: how soft what is out of focus gets. */
    val dofAmount: Float = 0.5f,
    /** One of [DOF_FOCUS_RIDER], [DOF_FOCUS_TARGET]. */
    val dofFocus: Int = DOF_FOCUS_RIDER,
    val volumetricClouds: Boolean = false,
    /** 0 fast, 1 balanced, 2 high: the steps taken through the clouds. */
    val cloudQuality: Int = 1,
    /** 0.5..1.5, 1 as the weather says: how much cloud there is and how thick. */
    val cloudDensity: Float = 1f,
    /** The clouds' shadows crossing the land. */
    val cloudShadows: Boolean = true,
    /** Waves, the sky's reflection and foam on lakes and rivers. */
    val enhancedWater: Boolean = true,
    /**
     * The frame rate the motion-blur shutter is worked out for: the shutter is open [shutterDegrees]/360 of
     * 1/this second. 0 (the default) is the video's own rate. A preview drawn at a lower rate than the film
     * will have names the film's rate here, so each frame blurs as it will in the film and only the
     * playback is choppier.
     */
    val shutterFps: Int = 0,
    /**
     * ULTRA-REALISTIC (contract 21): the Real 3D engine's highest tier for this film - the far ring of buildings, the
     * widest and densest trees, shrubs and ground cover, full-resolution occlusion - on top of whatever else is asked
     * here. Heavy on graphics memory: the caller keeps the renderers few. False (the default) is the export tier.
     */
    val realistic: Boolean = false,
    // ---- contract 24: the look of a valley after the rain (Real 3D, export only) ----
    /**
     * Wet asphalt that mirrors the sky, the slopes and the lane markings, with puddles. On its own it
     * wets the road whatever the weather; under rain or after the rain the road is at least as wet as
     * the weather makes it.
     */
    val wetRoad: Boolean = false,
    /** 0..1: how much water stands on the road when [wetRoad] is on, 1 a film of water all over. */
    val roadWetness: Float = 0.7f,
    /** 0..1: how many puddles, and how wide. */
    val puddles: Float = 0.4f,
    /** One of [REFLECTION_QUARTER], [REFLECTION_HALF], [REFLECTION_FULL]: the resolution the reflections are traced at. */
    val reflectionQuality: Int = REFLECTION_HALF,
    /** Rings spreading on the puddles, as when the last drops still fall. */
    val rainRipples: Boolean = false,
    /** Mist lying in the valleys and drifting in the wind, thickest on the valley floor. */
    val valleyFog: Boolean = false,
    /** 0..2: how thick the valley mist is, 1 as the weather says. */
    val fogDensity: Float = 1f,
    /** 50..800: metres above the valley floor the mist reaches. */
    val fogHeight: Float = 250f,
    /** 0..1: how far the cloud base comes down onto the ridges, 0 where the weather puts it. */
    val lowClouds: Float = 0f,
    /** One of [SEASON_AUTO], [SEASON_SPRING], [SEASON_SUMMER], [SEASON_AUTUMN]: the colour of meadows and leaves. */
    val season: Int = SEASON_AUTO,
    /** Tall, dense grass near the camera, where the land is meadow. */
    val lushGrass: Boolean = false,
    /** 0.5..4: how dense that grass is; past 3 only on a computer. */
    val grassDensity: Float = 1f,
    /** 0..1: wild flowers in that grass and along the verges. */
    val wildflowers: Float = 0f,
    /** Post-and-wire fences along the edges of meadows and fields near the road. */
    val fences: Boolean = false,
    /** Falls of white water where a mapped stream drops down a steep slope. */
    val waterfalls: Boolean = false,
    /** One of [GRADE_NEUTRAL], [GRADE_ALPINE_WET], [GRADE_WARM], [GRADE_COOL]: the colour grade. */
    val gradePreset: Int = GRADE_NEUTRAL
) {
    companion object {
        const val AA_SMAA = 0
        const val AA_TAA = 1
        const val DOF_FOCUS_RIDER = 0
        const val DOF_FOCUS_TARGET = 1
        const val REFLECTION_QUARTER = 0
        const val REFLECTION_HALF = 1
        const val REFLECTION_FULL = 2
        /** The season of the ride's own date and place. */
        const val SEASON_AUTO = 0
        const val SEASON_SPRING = 1
        const val SEASON_SUMMER = 2
        const val SEASON_AUTUMN = 3
        const val GRADE_NEUTRAL = 0
        /** Cool shadows, saturated greens, strong local contrast: an alpine valley after the rain. */
        const val GRADE_ALPINE_WET = 1
        const val GRADE_WARM = 2
        const val GRADE_COOL = 3
    }
}

/**
 * The figure and the details on the land. [rider] is one of [ModuleSceneRider]: the glowing dot,
 * or a motorcycle with its rider in [riderColor] (ARGB), drawn by the ArcGIS engine and grown with
 * the camera's distance so it reads from high above. [trackWall] stands a thin translucent wall
 * under the line (ArcGIS). [placeNames] and [buildings] show the towns' names and the buildings
 * in 3D, on both engines.
 */
class ModuleSceneExtras(
    val rider: Int,
    val riderColor: Int,
    val trackWall: Boolean,
    val placeNames: Boolean,
    val buildings: Boolean
)

/**
 * The figures of [ModuleSceneExtras.rider]: the dot, six kinds of bike, and from contract 17 the
 * models of real motorcycles, each with its own texture.
 */
object ModuleSceneRider {
    const val DOT = 0
    const val ADVENTURE = 1
    const val SPORT = 2
    const val NAKED = 3
    const val CRUISER = 4
    const val SCOOTER = 5
    const val ENDURO = 6
    const val MT700_ADVENTURE = 7
    const val MT800_X = 8
    const val NK800_SPORT = 9
    const val NK800_ADVANCE = 10
    const val R1300_R = 11
}

/** The engines of [ModuleScene.setEngine]. */
object ModuleSceneEngine {
    /** The app's own map engine: the rider's map or satellite on real relief. Light and quick. */
    const val MAPLIBRE = 0

    /**
     * ArcGIS: real sunlight with shadows, atmosphere, weather and water, the rider's Esri key
     * when there is one. Every frame waits for its imagery and relief, so it exports slower.
     */
    const val ARCGIS = 1

    /**
     * The Real 3D engine (contract 18): MOTO-HUB's own three.js world, drawn the way a game draws
     * one. The road is laid as real road, with its width, edges and camber, on terrain shaped to
     * it; forests and meadows grow where the land says they do, and the sky carries its sun,
     * clouds and weather. The sharpest picture of the three, and the heaviest: the first frame
     * builds the whole world along the route, every frame draws a great deal more, and an export
     * runs fewer frames at once. An app older than contract 18 draws it with ArcGIS.
     */
    const val ULTRA = 2
}

/** The skies of [ModuleScene.setWeather]. */
object ModuleSceneWeather {
    const val SUNNY = 0
    const val CLOUDY = 1
    const val RAINY = 2
    const val SNOWY = 3
    const val FOGGY = 4

    /**
     * Just after the rain (contract 24): a soaked land under a low grey deck, mist in the air and
     * nothing falling. The amount is how freshly it stopped, 1 soaked, 0 nearly dry. Real 3D wets the
     * road and the meadows; ArcGIS draws a heavy grey sky. An app older than contract 24 draws fog.
     */
    const val AFTER_RAIN = 5
}

/** The lenses of [ModuleScene.setLens]. */
object ModuleSceneLens {
    const val STANDARD = 0
    const val SPHERE = 1
    const val PLANET = 2
}

/** How an export is going. Called on the main thread. */
interface ModuleExportListener {
    fun onExportProgress(done: Int, total: Int)

    /** The video is in the gallery: its content Uri and size. */
    fun onExportFinished(uri: String, bytes: Long)

    fun onExportFailed(message: String)
}

interface ModuleExportJob {
    fun cancel()

    /**
     * The computer drawing this film (contract 23), or null when the phone is: known as soon as the export starts. A module that
     * is told the export failed asks it, to know whether the phone's own drawing is still an answer to offer.
     */
    fun renderedOn(): ModuleRenderServer? = null
}

/**
 * The scene's look.
 *
 * [mapStyle] is one of the STYLE_ constants; satellite falls back to the map when the rider has no
 * satellite source. [exaggeration] multiplies the relief (1 is true scale), [haze] thickens the
 * distance from 0 (clear) to 1, and a [progressiveTrack] is drawn only behind the rider, the rest
 * showing faintly ahead.
 */
class ModuleSceneLook(
    val mapStyle: Int,
    val exaggeration: Float,
    val haze: Float,
    val progressiveTrack: Boolean
) {
    companion object {
        const val STYLE_SATELLITE = 1
        const val STYLE_MAP = 2
        const val STYLE_NIGHT = 3
    }
}

/** Pins on the terrain: one slot per pin, a short label and an ARGB colour each. */
class ModuleSceneMarkers(
    val latitudes: DoubleArray,
    val longitudes: DoubleArray,
    val labels: Array<String>,
    val colors: IntArray
)

/** What a scene reports back. Called on the main thread. */
interface ModuleSceneListener {
    fun onSceneReady()

    /** The frame on screen, a few times a second while playing and once after every seek. */
    fun onSceneFrame(frame: Int, playing: Boolean)

    fun onSceneError(message: String)
}

/**
 * A camera move, as parallel arrays with one slot per frame.
 *
 * Heights are metres above the ground under that point, not above the sea: a module has no
 * elevation model and should not need one to keep a camera out of a mountain. The scene resolves
 * them against its own terrain as it plays.
 *
 * - camera and target: where the camera is, and the point it looks at.
 * - [fovDegrees]: the vertical field of view.
 * - [riderPositions]: where the rider's marker is, as a fractional index into the track given to
 *   [ModuleScene.setTrack]; also how far along the track is drawn as travelled.
 * - sun: azimuth clockwise from north and elevation above the horizon, both in degrees.
 */
class ModuleSceneShot(
    val framesPerSecond: Int,
    val cameraLatitudes: DoubleArray,
    val cameraLongitudes: DoubleArray,
    val cameraHeightsMeters: FloatArray,
    val targetLatitudes: DoubleArray,
    val targetLongitudes: DoubleArray,
    val targetHeightsMeters: FloatArray,
    val fovDegrees: FloatArray,
    val riderPositions: FloatArray,
    val sunAzimuthDegrees: FloatArray,
    val sunElevationDegrees: FloatArray
) {
    val frameCount: Int get() = cameraLatitudes.size
}
