# Rides, the 3D scene and the rider's AI

Contracts 11 to 15 lend a module three things it could not get any other way: the rider's rides
and routes, the app's 3D terrain scene, and the language model the rider set up. They are
**members of `MotoHubModuleHost`**, not capabilities: the app implements them and your module
calls them. A module that uses any of them must declare the contract that introduced what it
calls (see [contract.md](contract.md#versioning)); they need **ADV-SOLO 0.1.29 or later**.

| Host member | Since contract | What it is |
|---|---|---|
| [`rides`](#rides-and-routes-moduleridelibrary) | 11 | The rider's recorded rides and saved routes, read-only |
| [`scene`](#the-3d-scene-modulescenehost) | 11 | A 3D terrain scene your module directs |
| [`openedFor()`](#actions-on-one-ride-or-route) | 14 | The ride or route a `RIDE_ACTION` / `ROUTE_ACTION` feature was opened on |
| [`ai`](#the-riders-language-model-moduleai) | 14 | The rider's own language model, without the key |

Members added later to classes and interfaces above are marked with their contract in the KDoc
(`ModuleRideLibrary.engineRpm` is 12, `ModuleScene.export` is 13, and so on). Calling one on an
older app is an `AbstractMethodError`, so the contract you declare is the highest one you call.

---

## Rides and routes: `ModuleRideLibrary`

```kotlin
interface ModuleRideLibrary {
    fun recordedRides(limit: Int): List<ModuleRideEntry>
    fun savedRoutes(limit: Int): List<ModuleRideEntry>
    fun activeRoute(): ModuleRideEntry?
    fun track(entry: ModuleRideEntry, maxPoints: Int): ModuleRideTrack?
    fun engineRpm(track: ModuleRideTrack): FloatArray?   // contract 12
}
```

**Every call reads storage: call it off the main thread.**

- `recordedRides` is newest first. `savedRoutes` is in the rider's own order (planned in the NAV
  or imported from GPX). `activeRoute` is what the NAV is guiding along right now, or `null`.
- A `ModuleRideEntry` carries `id`, `kind`, `title`, `dateMillis`, `distanceMeters` and
  `durationMillis` (moving time of a ride, planned time of a route). `kind` is one of
  `KIND_RECORDED`, `KIND_SAVED_ROUTE`, `KIND_ACTIVE_ROUTE` and `KIND_PREVIEW_ROUTE` (a route
  planned and previewed but neither saved nor navigated, readable while its preview is open).
  `track` reads `kind` to know which store to open, so **hand an entry back as you received it**;
  never build one yourself.
- `track` thins the entry evenly to at most `maxPoints` and returns `null` when the entry no
  longer exists (deleted, or a route that stopped being active).

A `ModuleRideTrack` is parallel arrays, one slot per point, all the same length:

| Array | |
|---|---|
| `latitudes`, `longitudes` | Degrees. |
| `altitudesMeters` | Above sea level. From the GPS, or the route's elevation profile. When a track has none of its own, the app fills it from a terrain model if it is online. |
| `timesMillis` | Since the first point. On a route, the planned time at that point. |
| `speedsKph` | Measured speed. NaN on a route. |
| `leanDegrees` | The lean the app measured, NaN where it was not trustworthy or not recorded. |
| `maneuverIndices`, `maneuverTexts` | Where each turn instruction of a route applies and what it says. Empty on a recorded ride. |

**Unknown values are NaN, never absent**, so the arrays stay aligned. Test with `isNaN()` before
you draw or average.

`engineRpm(track)` gives the engine speed at each point of that track from the OBD log recorded
with the ride: NaN where the log has nothing, `null` when there is no engine data at all (a
route, or a ride recorded without an OBD adapter).

---

## The 3D scene: `ModuleSceneHost`

The app carries a map engine with real relief and satellite imagery. A module cannot ship a
second one and cannot reach the first, so the app lends the **scene** and your module supplies
the **direction**: a track to lay on the ground and a shot, which is where the camera is and what
it looks at on every frame.

```kotlin
val scene = host.scene.open()        // yours: close() it when you are done
scene.setListener(listener)
scene.setTrack(lats, lons, colors)   // one ARGB colour per point
scene.setShot(shot)
scene.play()

@Composable fun Film() { scene.Surface() }   // fills whatever space its parent gives it
```

Every call may be made before the map has loaded. The scene keeps the latest of each and applies
them when it is ready, which it reports with `ModuleSceneListener.onSceneReady()`. While playing
it reports the frame on screen a few times a second through `onSceneFrame(frame, playing)`, and
once after every `seek`. Listener calls arrive on the main thread.

`close()` frees the map and everything it loaded. Call it from `release()` too, for a scene still
open when the module is unloaded.

### The shot is computed up front

`ModuleSceneShot` is the whole camera move as parallel arrays, **one slot per frame**, at
`framesPerSecond`:

| Arrays | |
|---|---|
| `cameraLatitudes`, `cameraLongitudes`, `cameraHeightsMeters` | Where the camera is. |
| `targetLatitudes`, `targetLongitudes`, `targetHeightsMeters` | The point it looks at. |
| `fovDegrees` | The vertical field of view. |
| `riderPositions` | Where the rider's marker is, as a fractional index into the track given to `setTrack`. Also how much of the track is drawn as travelled. |
| `sunAzimuthDegrees`, `sunElevationDegrees` | Clockwise from north, and above the horizon. |

**Heights are metres above the ground under that point, not above the sea.** Your module has no
elevation model and does not need one to keep a camera out of a mountain: the scene resolves the
heights against its own terrain as it plays.

The scene plays the shot back itself. Steering the camera with one call per frame would stutter
whenever the bridge did, and seeking is just a frame number. `setShot` replaces the shot and keeps
the playback frame, clamped to the new length.

### How it looks

| Call | Since | |
|---|---|---|
| `setLook(ModuleSceneLook(mapStyle, exaggeration, haze, progressiveTrack))` | 12 | `STYLE_SATELLITE` (falls back to the map when the rider has no satellite source), `STYLE_MAP` or `STYLE_NIGHT`; relief multiplier (1 is true scale); haze 0 to 1; a progressive track is drawn only behind the rider. |
| `setMarkers(ModuleSceneMarkers(lats, lons, labels, colors))` | 12 | Labelled pins standing on the terrain. Replaces any given before. |
| `setLens(ModuleSceneLens.*)` | 14 | `STANDARD`, `SPHERE` (a 2:1 equirectangular 360° film, with the metadata players and headsets need) or `PLANET` (the sphere folded into a little planet). The last two draw each frame several times, so they play and export slower. |
| `setEngine(ModuleSceneEngine.*)` | 14 | `MAPLIBRE`, the app's own engine, or `ARCGIS`: sunlight with shadows, atmosphere, weather and water, with the rider's Esri key when there is one. Switching reloads the scene and keeps the film. |
| `setWeather(ModuleSceneWeather.*, cloudCover)` | 14 | `SUNNY`, `CLOUDY`, `RAINY`, `SNOWY`, `FOGGY`, with an amount 0 to 1. ArcGIS only; the app's own engine ignores it. |
| `setWeatherPath(weather, cloudCover)` | 14 | One weather and one amount per frame of the shot. Wins over `setWeather` where it fits the shot; `null` for one weather all along. |
| `setExtras(ModuleSceneExtras(rider, riderColor, trackWall, placeNames, buildings))` | 14 | The rider as a dot or a motorcycle (`ModuleSceneRider`, drawn by ArcGIS), a translucent wall under the line (ArcGIS), town names and 3D buildings (both engines). |
| `setBlend(second, mix)` | 14 | A second shot with the same frame count, blended by `mix` (0 to 1 per frame): a dissolve from one clip into the next. ArcGIS cannot blend and dips through black instead. `null` for none. |

### Exporting a video (contract 13)

```kotlin
val job = scene.export(
    ModuleExportSpec(width = 1920, height = 1080, framesPerSecond = 30, bitRate = 12_000_000,
        audioUri = null, audioStartMillis = 0, audioVolume = 1f,
        fileName = "my-ride.mp4", parallelRenderers = 2),
    overlay = { frame -> /* your titles, drawn over that frame */ },
    listener = exportListener
)
```

The export renders **frame by frame, not in real time**. Each frame is drawn off screen at the
video's size, the scene waits until its terrain and map are loaded, `overlay` is drawn over it
for that frame, and only then is the frame encoded with its exact time. A slow phone makes a slow
export, never a stuttering film.

- What is rendered is the scene's current track, shot, look and markers.
- `width` and `height` must be even. `framesPerSecond` may differ from the shot's: the shot is
  sampled between its frames.
- `audioUri` is a content Uri the rider picked, or `null` for a silent film. It starts
  `audioStartMillis` into the song at `audioVolume` and fades out over the film's last seconds.
  A negative `audioStartMillis` places the song that much after the film starts, with silence
  before it (contract 14).
- `parallelRenderers` is how many frames are drawn at once, each by its own off-screen scene.
  Most of a frame's time is spent waiting for tiles and relief, which waits just as well in
  parallel. The app may use fewer if the phone cannot start them all.
- The video lands in the rider's gallery under `Movies/MOTO-HUB`. The listener gets
  `onExportProgress(done, total)`, then `onExportFinished(uri, bytes)` or
  `onExportFailed(message)`, on the main thread. `job.cancel()` stops it.
- The screen has to stay on and MOTO-HUB in front while it renders: off-screen displays only
  draw while the phone's does. With the screen off the export pauses and carries on from the same
  frame when the rider is back.

### Export options (contract 15)

All of them have defaults, so a spec written for contract 13 or 14 compiles unchanged.

| Field | What it does |
|---|---|
| `draft` | A lighter 3D picture for checking a film before the real export: the ArcGIS engine draws at medium quality with no shadows, reflections or buildings, and each frame loads far less. The map engine ignores it. |
| `realTime` | The film is **filmed as it plays** instead of frame by frame: once, on an off-screen display the size of the video, straight into the hardware encoder. Minutes instead of the better part of an hour, but nothing waits for the map: imagery still loading, or a frame the phone did not manage to draw, stays in the video as it was. `parallelRenderers` is ignored. 720p is the safe size; larger ones may stutter on a busy scene. |
| `captureSpeed` | `realTime` only, 0.05 to 1: the film plays at this share of its pace while it is filmed, and every picture is stamped with the moment of the film it shows, so the video still comes out at the film's own pace. `0.5` gives the scene twice the time for each frame and takes twice as long. |
| `warmUp` | `realTime` only: the film is played once without filming first, so the map and relief it needs are already cached when it is filmed. Twice the time. |
| `hevc` | H.265 instead of H.264: about half the file for the same picture. Only a hardware encoder is used; on a phone without one for this size and rate the app falls back to H.264 at a higher `bitRate`, so size `bitRate` for H.265 when you ask for it. |

```kotlin
ModuleExportSpec(width = 1280, height = 720, framesPerSecond = 30, bitRate = 2_500_000,
    audioUri = song, audioStartMillis = 0, audioVolume = 0.8f,
    fileName = "my-ride.mp4", parallelRenderers = 1,
    realTime = true, captureSpeed = 0.5f, warmUp = false, hevc = true)
```

In both modes the sound is the song's own file, decoded, levelled, faded and encoded as AAC; the
phone plays nothing aloud.

---

## Actions on one ride or route

Contract 14 adds three placements to `ModuleFeature`
(see [capabilities.md](capabilities.md#pages-modulefeatures-and-moduleui)):

| `placement` | Where it appears |
|---|---|
| `RIDE_ACTION` | On a recorded trip's page, as an action on that ride. |
| `ROUTE_ACTION` | On a route's preview, saved or just planned, as an action on that route. |
| `TRIPS_HEADER` | A small pill with the feature's `title` at the top of Trips, beside the page's title: the way in to what your module keeps of the rides. |

When the screen of a `RIDE_ACTION` or `ROUTE_ACTION` feature opens, `host.openedFor()` returns
the entry it was opened on, ready to hand to `host.rides.track(...)`. It is `null` for a feature
opened anywhere else. A route only previewed comes as `KIND_PREVIEW_ROUTE`, and can be read while
its preview is open.

---

## The rider's language model: `ModuleAi`

```kotlin
interface ModuleAi {
    fun isReady(): Boolean
    fun complete(system: String, user: String, maxTokens: Int, json: Boolean): String
}
```

The model the rider configured in the app's AI settings: an OpenAI-compatible endpoint with the
rider's own key. **The key never reaches your module**: you ask, the app sends.

- `isReady()` says whether a key and a model are set, so `complete` can be tried at all. When it
  is `false`, offer what your module does without it; do not send the rider to set up AI just to
  use a module.
- `complete` is **blocking**: call it off the main thread. It returns the model's text or throws
  with the reason. `json = true` asks for a JSON object where the server honours it, so parse
  defensively all the same.
- The rider pays for every call. Ask once for something worth it, not on every frame or keystroke.
