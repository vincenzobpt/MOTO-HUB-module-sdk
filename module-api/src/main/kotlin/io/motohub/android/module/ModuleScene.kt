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
     * map engine, or ArcGIS - real sunlight with shadows, atmosphere, weather, water - with the
     * rider's Esri key when there is one. Switching reloads the scene; the film is kept.
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
     * A second camera blended over the shot (contract 14), for a dissolve from one clip into the
     * next: [second] has the shot's frame count, [mix] how much of it shows at each frame, 0 for
     * none to 1 for all of it. The app's own engine draws both pictures and blends them; ArcGIS,
     * which cannot, dips through black between them. Null for none.
     */
    fun setBlend(second: ModuleSceneShot?, mix: FloatArray?)
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
    val hevc: Boolean = false
)

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

/** The figures of [ModuleSceneExtras.rider]. */
object ModuleSceneRider {
    const val DOT = 0
    const val ADVENTURE = 1
    const val SPORT = 2
    const val NAKED = 3
    const val CRUISER = 4
    const val SCOOTER = 5
    const val ENDURO = 6
}

/** The engines of [ModuleScene.setEngine]. */
object ModuleSceneEngine {
    const val MAPLIBRE = 0
    const val ARCGIS = 1
}

/** The skies of [ModuleScene.setWeather]. */
object ModuleSceneWeather {
    const val SUNNY = 0
    const val CLOUDY = 1
    const val RAINY = 2
    const val SNOWY = 3
    const val FOGGY = 4
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
