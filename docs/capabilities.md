# Capabilities

A capability is an interface from `io.motohub.android.module` that your module returns from
`capability(type)`. The app asks for each one when it needs it. One object may implement several,
as the Android Auto module does.

| Capability | Since contract | The app asks for it when… |
|---|---|---|
| [`ModuleFeatures`](#pages-modulefeatures-and-moduleui) | 1 | it draws Modules, Settings, or the RIDE mode list |
| [`ModuleProjection`](#projection-moduleprojection) | 2 | the rider starts a projection onto the motorcycle's screen |
| [`ModuleNavigation`](#turn-by-turn-from-a-projection-modulenavigation) | 3 | the Ride Dashboard wants the projected app's guidance |
| [`ModuleAccessoryProbe`](#usb-accessories-moduleaccessoryprobe-and-moduleaccessorybridge) | 3 | the rider tests a USB head unit in Diagnostics |
| [`ModuleAudio`](#projected-audio-moduleaudio) | 6 | projected audio is shared, e.g. with the group intercom |
| [`ModuleNavigator`](#the-dashboard-on-someone-elses-screen-modulenavigator) | 7 | a third-party head unit opens MOTO-HUB through the Car App Library |
| [`ModuleAccessoryBridge`](#usb-accessories-moduleaccessoryprobe-and-moduleaccessorybridge) | 10 | diagnostics bridge a USB head unit to Android Auto |

Where only one module can serve (projection, navigator), the app takes the **first installed**
module that answers. That is a deliberate, predictable choice until a second one is meaningful.

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

None of these have default arguments, so always pass every one (see
[contract.md](contract.md#rules-that-are-easy-to-break)). Plain Compose is available for anything
genuinely your own, within the limits of [borrowed-libraries.md](borrowed-libraries.md).

Strings are yours to localise. The app's catalogue does not know them.

---

## Projection: `ModuleProjection`

For a module that can **fill the motorcycle's screen**: it produces a picture and the app composes
its own overlays on top, encodes, and streams it to the dashboard. Android Auto is one.

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
implementation.

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
