# Rides, the 3D scene and the rider's AI

Contracts 11 to 15 lend a module three things it could not get any other way: the rider's rides
and routes, the app's 3D terrain scene, and the language model the rider set up. Contract 22 adds
what a module needs to **make** rides and routes rather than only read them: the NAV's routing,
speed limits, elevation and place search, a place to save the result, and a flat map to choose
points on. They are **members of `MotoHubModuleHost`**, not capabilities: the app implements them
and your module calls them. A module that uses any of them must declare the contract that
introduced what it calls (see [contract.md](contract.md#versioning)); contracts 11 to 15 need
**ADV-SOLO 0.1.29 or later**, contract 22 needs **0.1.33 or later**.

| Host member | Since contract | What it is |
|---|---|---|
| [`rides`](#rides-and-routes-moduleridelibrary) | 11 | The rider's recorded rides and saved routes, read-only |
| [`scene`](#the-3d-scene-modulescenehost) | 11 | A 3D terrain scene your module directs |
| [`openedFor()`](#actions-on-one-ride-or-route) | 14 | The ride or route a `RIDE_ACTION` / `ROUTE_ACTION` feature was opened on |
| [`ai`](#the-riders-language-model-moduleai) | 14 | The rider's own language model, without the key |
| [`rideWriter`](#writing-rides-and-routes-moduleridewriter) | 22 | Saves simulated rides and routes into TRIPS and the NAV |
| [`routing`](#routing-speed-limits-and-elevation-modulerouting) | 22 | The NAV's routing, speed limits and elevation |
| [`places`](#place-search-moduleplaces) | 22 | The NAV's place search |
| [`maps`](#a-flat-map-modulemaphost) | 22 | A flat map the app draws and your module directs |
| `modules` | 22 | Other modules: whether they are installed, opening their features, what they add to yours; see [capabilities.md](capabilities.md#adding-to-another-module-moduleextensions-and-modulebridge) |

Members added later to classes and interfaces above are marked with their contract in the KDoc
(`ModuleRideLibrary.engineRpm` is 12, `ModuleScene.export` is 13, `ModuleRideLibrary.isSimulated`
is 22, and so on). Calling one on an older app is an `AbstractMethodError`, so the contract you
declare is the highest one you call.

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

`isSimulated(entry)` (contract 22) says whether an entry is a ride or route a module generated
(see [`ModuleRideWriter`](#writing-rides-and-routes-moduleridewriter)) rather than one the rider
rode or planned. `recordedRides` and `savedRoutes` list simulated entries along with the real
ones, so **a module that learns from the rider's rides should leave them out**.

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
| `setCredits(title, lines)` | 16 | Credits the film owes for what the module put in it (its music, its photos): `title` heads the block ("MUSIC"), `lines` are its entries, one short line each. They go into the credit roll that already names the maps, imagery and relief, on screen and in an export. An empty list takes the block away. |

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

### Drawing a film on another computer (contract 23)

A rider can pair the phone with MOTO-HUB Studio running its render server on their network; a
film is then drawn there and comes back to the phone as a finished video.

- `scene.renderServers()` lists the computers the app knows (paired, or found and not paired yet),
  each a `ModuleRenderServer` with `paired`, `ready`, `busy`, `queue`, a `problem`
  (`OFFLINE`, `BROWSER_MISSING`, `FFMPEG_MISSING`, `SOFTWARE_GL`, `NEEDS_PAIRING`) and the
  `overlays` it can draw. `watchRenderServers(listener)` keeps that list fresh until you close
  the handle; `pairRenderServer()` opens the app's own pairing screen.
- `spec.withRemoteRender(remote, renderOn)` asks for remote drawing. `remote` is a
  `ModuleRemoteRender`: your overlay **as data** (`overlayKind`, `overlayVersion`, `overlayJson`,
  the `files` it refers to), because code cannot travel. `renderOn` is
  `ModuleExportSpec.RENDER_AUTO` (a ready, idle, paired computer, otherwise the phone),
  `RENDER_PHONE`, or one server's `id`; a named server that cannot draw makes the export fail
  with a message rather than move elsewhere.
- `job.renderedOn()` says which computer is drawing the film, or `null` for the phone.

A computer draws only the overlay kinds it lists in `overlays` (`server.draws(kind, version)`):
Studio carries the drawing code for those itself. Today that is Flyby's overlay, so for other
modules a remote render is the 3D picture without your overlay unless Studio learns it.

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

---

## Writing rides and routes: `ModuleRideWriter`

```kotlin
interface ModuleRideWriter {
    fun saveSimulatedRide(ride: ModuleSimulatedRide): String?
    fun saveSimulatedRoute(route: ModuleSavedRoute): String?
    fun removeSimulatedRoute(id: String): Boolean
}
```

For a module that **generates** rides: a simulator, a planner that keeps its routes. **Every call
writes storage: call it off the main thread.** Nothing throws; what could not be saved comes
back as `null` (or `false`).

Nothing here records a real ride. A saved ride is marked as simulated wherever the app lists it,
belongs to no motorcycle and stays out of every total and record. Tell the rider's own rides and
the generated ones apart with [`host.rides.isSimulated(entry)`](#rides-and-routes-moduleridelibrary).

- `saveSimulatedRide` puts a ride in TRIPS and returns its id.
- `saveSimulatedRoute` keeps a route among the NAV's **simulated routes**, a list of its own
  beside the routes the rider saved, so a generated route never pushes one the rider saved out.
  It returns the route's id. The route carries only its arrival as a maneuver, like a route
  imported from GPX.
- `removeSimulatedRoute(id)` removes a simulated route **this module saved** and says whether
  something was removed.

A `ModuleSimulatedRide` is parallel arrays, one slot per sample, **all the same length**:

| Field | |
|---|---|
| `title`, `startedAtMillis` | The ride's name, and when it started. |
| `latitudes`, `longitudes` | Degrees. |
| `altitudesMeters` | Above sea level, NaN where unknown. |
| `timesMillis` | Since `startedAtMillis`, strictly increasing, never before zero. |
| `speedsKph` | |
| `leanDegrees` | Positive to the right, NaN where there is none. |
| `engineRpm` | NaN where there is none. |
| `accuracyMeters`, `satellites` | What the GPS fix would have reported. |
| `startPlace`, `endPlace` | Where it started and ended, by name; `null` when unknown. |

Write samples at **10 Hz**. The app thins the GPS track the way its own recorder does and keeps
the sensor log (speed, lean, engine speed) at full rate. A ride comes back `null` when its arrays
differ in length, it has fewer than two samples, or its clock starts before zero or runs
backwards.

A `ModuleSavedRoute` is a planned route as plain arrays: `title`, `latitudes`, `longitudes`,
`altitudesMeters`, `distanceMeters`, `durationSeconds` and `destinationLabel` (the destination's
name as the NAV shows it).

- `altitudesMeters` is **either empty or one value per point**; the app keeps it only when every
  value is a number, so fill the gaps first.
- A route with fewer than two points, a coordinate that is not a real position, or latitudes and
  longitudes of different lengths comes back `null`.
- A `distanceMeters` or `durationSeconds` that is not a number is replaced by the length of the
  line and zero.

---

## Routing, speed limits and elevation: `ModuleRouting`

```kotlin
interface ModuleRouting {
    fun route(latitudes: DoubleArray, longitudes: DoubleArray, preference: Int): ModuleRouteResult
    fun speedLimits(latitudes: DoubleArray, longitudes: DoubleArray): FloatArray?
    fun elevations(latitudes: DoubleArray, longitudes: DoubleArray): DoubleArray?
}
```

The NAV's own routing, so a module that plans a route gets what the rider's NAV would. The app
picks the server (the rider's own key, or the shared demo server) and keeps to its rate limits.
**Every call goes to the network and blocks: call it off the main thread.** A failure is an
ordinary answer, never an exception.

- **`route`** takes at least two points, in order. The first is the start, the last is the
  destination, any others are points to pass through. `preference` is
  `ModuleRoutePreference.FASTEST` or `SCENIC`; pass the constant, not a bare number. Latitudes
  and longitudes that differ in number, or a point that is not a real position, come back as a
  failed result, not a crash.
- **`speedLimits`** gives the limit in km/h of **each segment** of a route, so one value fewer
  than there are points, NaN where the road has none known. It is **all or nothing**: when any
  stretch of a long route fails to answer you get `null`, never a partial list, and your
  simulation should fall back on its own cruising speed for the whole route.
- **`elevations`** gives metres above sea level at each point, or `null` when the elevation
  service did not answer.

A `ModuleRouteResult` has `ok`, and when it is `true` the route as `latitudes` and `longitudes`
with its `distanceMeters` and `durationSeconds`. When it is `false`, `errorKind` says why
(`ModuleRouteError`) and `error` is a sentence in the rider's words, ready to show:

| `errorKind` | |
|---|---|
| `NONE` | There was no error. |
| `NO_NETWORK` | The phone could not reach the server. |
| `RATE_LIMITED` | The server is busy; try again in a minute. |
| `NO_API_KEY` | The server needs a routing key the rider has not set. |
| `TOO_LONG` | The route is too long for a single request. |
| `OTHER` | Anything else, including "no road route between those points". |

The shared demo server allows about one request a second, and the app **spaces calls to it out
itself**, across every module. A call can therefore take longer than the network does, and you
need no pacing of your own. Do not ask for the same route again and again.

---

## Place search: `ModulePlaces`

```kotlin
interface ModulePlaces {
    fun search(query: String, nearLatitude: Double, nearLongitude: Double, limit: Int): ModulePlaceResult
    fun reverse(latitude: Double, longitude: Double): String?
}
```

The NAV's place search. **Both calls go to the network and block: call them off the main
thread.**

- `search` finds places matching `query`, nearest first to the point you give; pass NaN for
  both coordinates for no bias. `limit` is how many you want, up to 20. A blank query, or one
  over 200 characters, is refused with a message in `error`. **Debounce what the rider types**:
  every call is one request.
- `reverse` gives the name of the place at a point (a town, a pass), or `null` when there is
  none or the service did not answer.

A `ModulePlaceResult` is `ok`, an `error` in the rider's words when it is not, and three parallel
arrays, `labels`, `latitudes` and `longitudes`, one slot per place.


Since 0.1.33 `ModulePlaces` also has `lastKnownPosition()`: the rider's last known position as
`[latitude, longitude]`, or `null` when the app has none (no permission, no fix, or one a week old
or more). It never asks for the permission and never waits for a fix; it is meant for a coarse
use such as choosing a starting country, not for tracking.

---

## Sights near a road: `ModuleSights`

```kotlin
interface ModuleSights {
    fun along(latitudes: DoubleArray, longitudes: DoubleArray, radiusMeters: Int, kinds: Int): ModuleSightResult
}
```

`host.sights` (contract 22) finds things worth a detour within `radiusMeters` of a road, from
OpenStreetMap. The app owns the Overpass side: which instances to ask, spacing, caching. **The
call goes to the network and blocks: call it off the main thread.** A failure is an answer, never
an exception.

- `kinds` is a mask of `ModuleSightKind.VIEWPOINT` (viewpoints, waterfalls, caves, natural
  arches), `HERITAGE` (castles, ruins, abbeys, monuments, museums, notable churches) and `PASS`
  (named passes the road itself climbs; the radius does not widen it).
- The radius is clamped to 500 m..15 km. Every sight has a name; unnamed ones are left out.
- `ModuleSightResult` is `ok`/`error`, `complete` (false when the road ran on past what the search
  covered) and parallel arrays `names`, `latitudes`, `longitudes`, `kinds`, `elevations` (NaN
  when unknown). Sights come in no particular order: order them along your own road.

---

## A flat map: `ModuleMapHost`

The 3D scene is for films. For a screen where the rider **chooses points or looks at a route**,
the app lends a flat map: its own map engine, with the rider's style and tiles, the lines and
pins you give it and the touches it reports back.

```kotlin
val map = host.maps.open()           // yours: close() it when you are done
map.setListener(listener)
map.setLines(arrayOf(ModuleMapLine(lats, lons, colorArgb, widthDp, dashed)))
map.setPins(arrayOf(ModuleMapPin(lat, lon, colorArgb, "Start")))
map.fit(lats, lons)                  // or map.center(lat, lon, zoom)

@Composable fun Picker() { map.Surface() }   // fills whatever space its parent gives it
```

- `setLines` and `setPins` **replace** everything given before. A coordinate that cannot be drawn
  is left out; a line with fewer than two drawable points is not drawn. A pin's `label` is its
  caption; an empty one has none.
- `fit` frames the points you give; `center` moves to a point at a zoom (0 to 22).
- The map keeps its lines, pins and camera in the `ModuleMap`, not in the composition, so a screen
  that is left and entered again finds the map as it was.
- Touches come to the `ModuleMapListener` you set: `onTap` and `onLongPress` with the position,
  and `onPinTap(index)` with the pin's **position in the array last given to `setPins`**. Do not
  block in them; `setListener(null)` stops them.
- `close()` frees the map and everything it loaded. After it every call does nothing. Call it
  from `release()` too, for a map still open when the module is unloaded.

The classes are plain: `ModuleMapLine(latitudes, longitudes, colorArgb, widthDp, dashed)` and
`ModuleMapPin(latitude, longitude, colorArgb, label)`. None of them have default arguments, so
pass every one (see [contract.md](contract.md#rules-that-are-easy-to-break)).

For a worked example of all four, see [example-route-simulator.md](example-route-simulator.md).
