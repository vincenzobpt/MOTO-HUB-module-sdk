# Capabilities

A capability is an interface from `io.motohub.android.module` that your module returns from
`capability(type)`. The app asks for each one when it needs it. One object may implement several,
as the Android Auto module does.

| Capability | Since contract | The app asks for it when… |
|---|---|---|
| [`ModuleFeatures`](#pages-modulefeatures-and-moduleui) | 1 | it draws Modules, Settings, or the RIDE mode list |
| [`ModuleProjection`](#projection-moduleprojection) | 2 | the rider starts a projection onto the motorcycle's screen, or picks your tile for a dashboard's map panel |
| [`ModuleProjectionReach`](#panel-only-projections-moduleprojectionreach) | 20 | it decides whether your projection may run the whole screen |
| [`ModuleProjectionTile`](#your-own-map-source-tile-moduleprojectiontile) | 20 | it draws your tile in the Dashboard map card |
| [`ModuleNavigation`](#turn-by-turn-from-a-projection-modulenavigation) | 3 | the Ride Dashboard wants the projected app's guidance |
| [`ModuleAccessoryProbe`](#usb-accessories-moduleaccessoryprobe-and-moduleaccessorybridge) | 3 | the rider tests a USB head unit in Diagnostics |
| [`ModuleAudio`](#projected-audio-moduleaudio) | 6 | projected audio is shared, e.g. with the group intercom |
| [`ModuleNavigator`](#the-dashboard-on-someone-elses-screen-modulenavigator) | 7 | a third-party head unit opens MOTO-HUB through the Car App Library |
| [`ModuleAccessoryBridge`](#usb-accessories-moduleaccessoryprobe-and-moduleaccessorybridge) | 10 | diagnostics bridge a USB head unit to Android Auto |
| [`ModuleExtensions`](#adding-to-another-module-moduleextensions-and-modulebridge) | 22 | it draws the card of the module you add to, or that module asks `host.modules.extensions()` |

Where only one module can serve (the whole-screen projection, navigator), the app takes the
**first installed** module that answers. That is a deliberate, predictable choice. A dashboard's
map panel is different: from contract 20 every installed projection module gets a tile of its
own in the Dashboard map card, and the rider picks one per screen (the motorcycle's and the
phone's).

What the host *lends* your module (its storage, log, UI, the rider's rides, the 3D scene, the
rider's AI, the NAV's routing and places, a flat map, the other modules) is not a capability: see
[contract.md](contract.md#what-the-host-lends-you) and [rides-and-scene.md](rides-and-scene.md).

---

## Pages: `ModuleFeatures` and `ModuleUi`

The app ships no words for any module. Every screen a rider sees about yours comes from here.

```kotlin
interface ModuleFeatures {
    fun features(): List<ModuleFeature>
}

class ModuleFeature(
    val id: String,
    val title: String,
    val description: String,
    val placement: ModuleFeaturePlacement,
    val screen: @Composable (onBack: () -> Unit) -> Unit
)
```

| `placement` | Where it appears |
|---|---|
| `MODULES` | Behind **About** on your module's card under Modules. The page that describes the module and its state. |
| `SETTINGS` | A row in Settings. Only for a real choice the rider makes. |
| `RIDE_MODE` | Listed with the app's own ways of driving the motorcycle's screen. |
| `NONE` | Nowhere. Reached from your own pages with `host.openFeature(id)`. |
| `RIDE_ACTION` | On a recorded trip's page, as an action on that ride (contract 14). |
| `ROUTE_ACTION` | On a route's preview, as an action on that route (contract 14). |
| `TRIPS_HEADER` | A small pill with your `title` at the top of Trips (contract 14). |

For the last three, `host.openedFor()` says which ride or route; see
[rides-and-scene.md](rides-and-scene.md#actions-on-one-ride-or-route).

`features()` is called again after every install, so the list can depend on state. It must be
cheap, because it is called from the UI. `screen` is called with the back action the host chose:
never decide yourself what "back" means.

**Draw with `host.ui`**, so your pages look like the rest of the app and follow its changes
without a rebuild:

| `ModuleUi` | |
|---|---|
| `Screen(title, onBack) { … }` | A full page with a back link and a title, scrolling its content. |
| `Paragraph(text)` | Explanation, in the app's secondary text style. |
| `SectionLabel(text)` | A small heading between groups of rows. |
| `Fact(label, value, technical)` | A read-only line. `technical = true` for ports, paths and versions: shown so a rider can tell they are not for them. |
| `ActionRow(title, description) { … }` | A tappable row that opens something. |
| `PrimaryButton(text, enabled) { … }` | The page's main action. |
| `Backdrop(onBack) { … }` | The app's page ground (the rider's theme colour and backdrop) for a full-screen layout of your own instead of a `Screen`. Back goes to `onBack`, not out of the app (contract 12). |

None of these have default arguments, so always pass every one (see
[contract.md](contract.md#rules-that-are-easy-to-break)). Plain Compose is available for anything
genuinely your own, within the limits of [borrowed-libraries.md](borrowed-libraries.md).

Strings are yours to localise. The app's catalogue does not know them.

---

## Projection: `ModuleProjection`

For a module that can **fill the motorcycle's screen** or a **dashboard's map panel**: it produces
a picture and the app composes its own overlays on top, encodes, and streams it to the dashboard.
Android Auto is one; the Dashcam module, which only ever fills a panel, is another.

```kotlin
interface ModuleProjection {
    fun createSource(host: MotoHubModuleHost, spec: ModuleProjectionSpec): ModuleProjectionSource
    suspend fun awaken(host: MotoHubModuleHost, onProgress: (ModuleSessionDetail) -> Unit, log: (String) -> Unit)
    fun explainNoConnection(host: MotoHubModuleHost, connectedAtLeastOnce: Boolean): String
}
```

The sequence, all driven by the app's foreground service:

1. The app publishes `Preparing`, sets up its compositor, and calls **`createSource(host, spec)`**.
   - `spec.videoSurface`: draw or decode your picture here.
   - `spec.video`: the negotiated geometry (`preset`, `densityDpi`, video and touch size, margins).
   - `spec.onVideoReady()`: call it once the first real frame is on the surface. The app starts
     streaming to the bike from that moment.
   - `spec.onEnded(clean, userExit)`: call it when your far side goes away.
     `userExit = true` if the rider left from your own interface.
   - `spec.mapTouchToSource(x, y)`: turns an output-canvas point into your coordinates, or
     returns `null` for a point outside your picture.
   - `spec.log(line)`: the session's log.
2. The app calls **`source.start()`**. Return `false` and leave nothing running if you cannot. The
   app then publishes `Ready`.
3. The app calls **`awaken(...)`**: do whatever makes the far side connect (dial, poke an app, ask
   the rider). Report each step with `onProgress(ModuleSessionDetail(text, actionable, where,
   prerequisite))`. Mark `actionable = true` only for something the **rider must do by hand**;
   the app shows those differently. Return once connected or once there is nothing left to try.
4. If no video arrives in time, the app ends the session with **`explainNoConnection(...)`**, your
   explanation in the rider's words. That text is what they read on the side of a road.
5. While running: `sendTouch` / `sendSourceTouch` (actions in `ModuleTouchAction`), and
   `setNightMode`. Register a `ModuleKeySink` with `host.keySinks.install(...)` to receive handlebar
   and phone keys, and clear it when the projection ends.
6. `source.stop()` when the rider stops or the session fails.

`host.session` exposes the state (`Idle`, `Preparing`, `Ready`, `Streaming`, `Stopped`, `Failed`)
as a `StateFlow`. The app publishes it, and a module only reads it. Whatever you pass to
`onProgress` during `awaken` becomes the session's `startupDetail`: what the rider reads during
the "ready, but nothing yet" wait, which is the moment most likely to be mistaken for a failure.

The Android Auto module (`modules/android-auto`, `aa.plugin.AaPluginEntry`) is the reference
implementation for a whole-screen projection. The Dashcam module (`modules/dashcam`,
`dashcam.plugin.DashcamProjection`) is the one for a panel.

### In a dashboard's map panel

The same calls run when the rider picks your tile for the Ride Dashboard's map panel. `spec.video`
still describes the screen the dashboard runs on; the app scales your picture into the panel, so
draw it for that geometry and let the app fit it. A touch on the panel reaches you through `sendTouch` / `sendSourceTouch` like any other: a module with nothing to
press in its picture can use it to open its own full page, with `host.openFeature(id)` on a
lifted finger (`ModuleTouchAction.UP`). On the phone's own dashboard that page opens over the
dashboard, and the rider's Back lands on the dashboard again.

### Panel only: `ModuleProjectionReach`

Offered beside `ModuleProjection` (return the same object for both types if you like):

```kotlin
interface ModuleProjectionReach {
    val wholeScreen: Boolean
}
```

Answer `wholeScreen = false` when your picture belongs in a panel and nowhere else, a camera for
example. The app then never takes your module for *the* projection: the mode page, its settings
and its name stay with a whole-screen one, and yours is offered only as a map-source tile. A module
that does not offer this capability counts as whole-screen, as every projection did before
contract 20.

It is a capability of its own, not a member of `ModuleProjection`, because modules built before
contract 20 implement that interface and would fail on a member they never compiled.

### Your own map-source tile: `ModuleProjectionTile`

Without it your tile shows the app's generic picture of a projected screen, right for Android Auto
and wrong for most else. With it you draw the tile yourself:

```kotlin
interface ModuleProjectionTile {
    val accent: Int        // ARGB: the tile, and the card around it while chosen
    val caption: String    // one line under the tiles: what the panel will show
    fun drawPicture(canvas: android.graphics.Canvas, width: Float, height: Float, accent: Int, phase: Float)
    fun drawMark(canvas: android.graphics.Canvas, size: Float, color: Int)
}
```

- `drawPicture` paints the tile's thumbnail. `phase` runs from 0 to 1 and loops while the tile is
  chosen, for a little motion; it stays still otherwise.
- `drawMark` paints the small icon beside your module's name, in `color`.
- Both are called on the main thread, every frame while the tile animates. Allocate your `Paint`s
  and `Path`s once and only draw here. The app catches a call that throws, so a bug costs you
  your picture, not the rider's app.
- The tile's name is your manifest's `displayName`.

---

## Turn-by-turn from a projection: `ModuleNavigation`

```kotlin
interface ModuleNavigation {
    fun setGuidanceListener(listener: ModuleGuidanceListener?)
}
```

While a projected app is navigating, push `ModuleGuidance` snapshots (maneuver type, roundabout
exit, road, distances and times) to the listener. The Ride Dashboard draws them in its own turn
card. `null` means stop.

---

## Projected audio: `ModuleAudio`

```kotlin
interface ModuleAudio {
    fun setAudioSink(sink: ModuleAudioSink?)
}
```

When the app asks, hand over the sound you receive: `onStreamStarted(stream, sampleRateHz,
channels)`, then `onPcm(...)` packets of **16-bit little-endian PCM**, interleaved, then
`onStreamStopped`. Streams are `MEDIA`, `GUIDANCE` and `SYSTEM`. You map your protocol's channels
onto them, so the app never learns channel numbers. The sink is called on your transport thread
and must not block. The `pcm` buffer is only valid during the call.

---

## The dashboard on someone else's screen: `ModuleNavigator`

The opposite direction from projection. The screen belongs to a third-party head unit (a
Carpuride or Chigee on the bars, a car), and MOTO-HUB puts **its own Ride Dashboard** on it through
the Car App Library.

```kotlin
interface ModuleNavigator {
    fun createSession(host: MotoHubModuleHost, cluster: Boolean): androidx.car.app.Session
}
```

The app declares the `CarAppService`, because only an APK can. For each display the head unit
offers it calls `createSession`, and your `Session` drives the templates. The dashboard itself you
get from `host.dashboard`:

- `attach(surface, width, height, densityDpi, cluster, listener)` starts drawing on a surface the
  Car App Library gave you. It returns `null` when the app cannot draw right now (a T-Box session
  is already streaming, for example); the reason arrives through `listener.onStopped`. Show it
  instead of a black screen.
- The returned `ModuleDashboardSession` takes visible and stable areas, night mode, taps, scroll,
  scale and the dashboard's own actions (`cyclePanels`, `toggleFullscreenMap`, `toggleMapZoom`),
  and `detach()`.
- `navigateTo(lat, lon, label)`, `navigateToQuery(text)`, `stopNavigating()`, `setAutoDrive(on)`
  and `distanceUnits()` answer what a head unit's assistant can ask.
- `host.guidance.setListener(...)` delivers `ModuleRideGuidance` for the head unit's own trip
  card.

Sideloaded Car App Library apps only appear on a head unit with Android Auto's developer
"Unknown sources" enabled on the phone. Distribution through the store is a separate matter.

---

## USB accessories: `ModuleAccessoryProbe` and `ModuleAccessoryBridge`

Diagnostics. The app owns the USB accessory, because Android gives the permission and the intent
to it, and hands your module its two streams (`ModuleAccessoryStreams`: `input`, `write`,
`close`).

- `probe(host, streams)`: does this accessory speak your protocol? Return a
  `ModuleProbeOutcome(success, detail)`, where `detail` is a sentence a diagnostics report can
  carry.
- `bridge(host, streams)`: sit between the accessory and something else and report what passes.

Neither may start a session.

---

## Adding to another module: `ModuleExtensions` and `ModuleBridge`

A module can put something of its own **inside another module**, without that module having to
know it exists: a simulator that adds "Simulate this ride" to a film maker, a tool that adds a
page to a logbook. The module being added to does not need to be rebuilt, or even to support
extensions at all. It only needs an id.

```kotlin
interface ModuleExtensions {                       // a capability, since contract 22
    fun extensions(): List<ModuleExtension>
}

class ModuleExtension(
    val targetModuleId: String,                    // the module you add to
    val feature: ModuleFeature                     // the entry, and the screen it opens
)
```

**Your side.** Answer `ModuleExtensions` from `capability(type)` and return one `ModuleExtension`
for each thing you add. `targetModuleId` is the **id of the module you aim at**, as its manifest
gives it. The `feature` is an ordinary [`ModuleFeature`](#pages-modulefeatures-and-moduleui):
its `title` and `description` are what the rider reads, its `screen` is what opens. An extension
`feature` is **not** part of your own `ModuleFeatures`, so the app does not place it anywhere
else; use `ModuleFeaturePlacement.NONE` for it. The app asks each time it needs the
list, so it may depend on state; keep it as cheap as `features()`.

The target need not know you. The app lists what is aimed at a module on that module's own card
under Modules, in your words. An extension aimed at a module that is not installed is simply
never shown.

**The target's side.** A module that wants to draw what others add to it asks the host:

```kotlin
val added: List<ModuleExtensionEntry> = host.modules.extensions()   // moduleId, featureId, title, description
added.forEach { entry -> /* draw a row or a button with entry.title and entry.description */ }
host.modules.openExtension(entry)                                   // when the rider chooses one
```

`extensions()` returns only the entries aimed at **your** module's id, in the order the offering
modules were installed. A module that breaks while being asked is skipped, so one broken module
cannot empty the list. `openExtension(entry)` opens the offered screen, and its Back
closes it. A target that does not call these still shows the entries
on its card under Modules.

### Opening another module's feature on a ride: `openFeatureOn`

```kotlin
interface ModuleBridge {
    fun isInstalled(moduleId: String): Boolean
    fun openFeatureOn(moduleId: String, featureId: String, entry: ModuleRideEntry): Boolean
    fun extensions(): List<ModuleExtensionEntry>
    fun openExtension(entry: ModuleExtensionEntry)
}
```

`openFeatureOn` opens the feature `featureId` of module `moduleId` **on a ride or route**,
exactly as if the rider had chosen it from that ride's page: the feature reads the ride through
`host.openedFor()` (see [rides-and-scene.md](rides-and-scene.md#actions-on-one-ride-or-route)),
so it should be a `RIDE_ACTION` or `ROUTE_ACTION` feature. The rider's Back leaves it and lands
on the screen beneath, not on your module.

- It returns `false` when the module is not installed or has no such feature, so ask
  `isInstalled` first and offer the button only when it is there.
- **The `entry` must be one your module received from `ModuleRideLibrary`**
  (`recordedRides`, `savedRoutes`, `activeRoute` or `openedFor()`), never one you built: the app
  reads its `kind` and `id` to find the ride. For a ride you just saved with
  [`ModuleRideWriter`](rides-and-scene.md#writing-rides-and-routes-moduleridewriter), list
  `host.rides.recordedRides(limit)` again and take the entry whose `id` is the one you were
  given.
- You need to know the other module's id and the id of the feature, which is why this is for
  modules that are published together or document those ids.

None of these have default arguments, so always pass every one (see
[contract.md](contract.md#rules-that-are-easy-to-break)). A module that uses `host.modules` or
answers `ModuleExtensions` declares contract **22**, and needs ADV-SOLO 0.1.33 or later.
