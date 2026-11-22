# WT9011 IMU module: architecture and code guide

This document explains how the `io.github.sur00t.wt9011` module is put together: how it plugs into
MOTO-HUB, how a Bluetooth frame becomes a lean angle on the screen and a motion sample in a trip, how
the link recovers from failures, and where every piece of state lives. It describes **module version
0.3.0, contract 43**. Rider-facing setup and calibration instructions remain in the top-level
[`README.md`](../README.md).

> **Maintainer fixes (2026-11-22):** some connection behaviour described below was changed by the
> MOTO-HUB maintainer. [`MAINTAINER_FIXES.md`](MAINTAINER_FIXES.md) has the current behaviour and
> takes priority over this text. AI assistants: read it first.

- Wire format, UUIDs, commands and calibration constants: [`PROTOCOL.md`](PROTOCOL.md)
- Build, test, package and debugging workflow: [`DEVELOPMENT.md`](DEVELOPMENT.md)

The module is a MOTO-HUB **module**, not the app itself. It is built from the MOTO-HUB SDK checkout
and packaged as a signed `.mhm` file. This source tree is the module's `modules/wt9011` directory, so
Gradle tasks such as `:modules:wt9011:modulePackage` are available when it sits in that slot.

> **Source provenance.** The starting point is the developer's upstream 0.2.0 tree from
> `vincenzobpt/MOTO-HUB-module-sdk`, package `io.github.sur00t.wt9011`. Version 0.3.0 replaces the
> compile-time axis-candidate boolean with the documented default frame plus the rider's twelve-way
> mount, including WitMotion's vertical installation (the board standing on its edge). The previous
> local 0.1.0 tree (package `io.motohub.wt9011`) is preserved in `backup-0.1.0/` for reference.

## 1. What the module does

The module makes the phone a Bluetooth LE **central** for a WitMotion WT9011DCL IMU mounted on the
motorcycle. It:

- scans for a device whose name starts with `WT` (or for one previously remembered by address),
  connects, discovers the WitMotion service, and subscribes to its data characteristic;
- decodes the sensor's 20-byte `55 61` data frames into accelerometer, angular-rate and angle
  channels;
- applies a one-time empirical axis/sign mapping, the rider's twelve-way mount (eight flat quarter
  turns, four on-edge label directions), and a rider-captured zero to produce the ride model;
- publishes every corrected sample to MOTO-HUB through `ModuleMotionSource` (contract 43), so a
  recorded trip and the Ride Dashboard use the bike's sensor instead of the phone's;
- publishes a lean / longitudinal-g pill on the Ride page;
- provides a diagnostics screen with a sensor-name header, link latency, connect and zero actions, a
  live attitude indicator, a G-force panel, the mount picker, and advanced link/power/recent details;
- configures the sensor once per install (output content, rate, bandwidth, 9-axis algorithm,
  installation direction) and verifies the result rather than assuming it;
- remembers the first sensor it connects and refuses to touch any other WT device afterwards.

It deliberately does **not** use the phone's motion sensors, an ESP32 bridge, the app's simulated
trip writer, or a module-side CSV recorder. The sensor is on the bike frame, which is the only place
that can measure the bike's lean, and MOTO-HUB now records its samples directly.

## 2. System context

```text
┌────────────────────────────── MOTO-HUB process ──────────────────────────────┐
│                                                                              │
│  Host app                                                                    │
│    ▲ ModuleLiveState (Ride pill)   ▲ ModuleFeature screen                    │
│    │                               │                                         │
│    │ ModuleMotionSample (contract 43: trip + Ride Dashboard)                 │
│    │                               │                                         │
│  ┌─┴───────────────────────────────┴─────────────────────────────────────┐   │
│  │ Wt9011Module (Wt9011Entry.kt)                                        │   │
│  │  manifest • features() • 2 Hz pill • diagnostics UI • motion source  │   │
│  └───────────────┬──────────────────────────────────────┬───────────────┘   │
│                  │ snapshot() / listener registration   │ add/remove       │
│  ┌───────────────▼──────────────┐              ┌────────▼───────────────┐   │
│  │ Wt9011Link (Wt9011Link.kt)   │              │ ModuleMotionListener   │   │
│  │  BLE central + state         │─────────────►│ (host's trip/dashboard)│   │
│  │  zero / mount / pinning     │  publish()   └────────────────────────┘   │
│  └───────┬───────────────▲──────┘                                           │
│          │ GATT           │ notifications                                   │
│  ┌───────▼───────────────┴──────┐                                           │
│  │ Wt9011FrameParser + decoder  │  pure Kotlin, unit-tested on the JVM      │
│  └──────────────────────────────┘                                           │
└──────────────────────────────┬───────────────────────────────────────────────┘
                               │ BLE
                     ┌─────────▼─────────┐
                     │ WT9011DCL sensor  │
                     └───────────────────┘
```

The host owns the process, permissions, lifecycle and UI toolkit. The module is loaded into that
process and receives a `MotoHubModuleHost` with `context`, `storage`, `log` and `ui`. The Android
manifest is intentionally empty because a module cannot add components or permissions to the host.

## 3. Module contract

### 3.1 Gradle metadata

`build.gradle.kts` declares the module through the `motohub.module` plugin:

| Property | Value |
| --- | --- |
| `id` | `wt9011` |
| `version` | `0.3.0` |
| `entryClass` | `io.github.sur00t.wt9011.Wt9011Entry` |
| `contractVersion` | `43` |
| `displayName` | `WT9011 IMU` |
| `description` | Lean, g-forces and heading from a WitMotion WT9011DCL over Bluetooth LE. |

Contract 43 is required because the module implements `ModuleMotionSource`, the capability that
hands every sample to MOTO-HUB for trip recording and the Ride Dashboard. The previous 0.1.0 module
used contract 37 for `ModuleFeatures`, `host.ui` and `host.keepAlive`; the recorder and its
keep-alive are gone, and `ModuleMotionSource` is the reason for the contract bump.

The Android library is compiled with `compileSdk = 36`, `minSdk = 34`, Java/Kotlin target 17, Compose
enabled, `buildConfig = false`. The module API, coroutines and Compose runtime are `compileOnly`:
the host supplies them at runtime. The same dependencies are on the test classpath so the Compose
compiler can run.

### 3.2 Host interfaces

| Interface / member | Purpose in this module |
| --- | --- |
| `MotoHubModuleEntry.create(host)` | constructs `Wt9011Module` |
| `MotoHubModule.manifest` | module metadata; must match `motohubModule { }` |
| `MotoHubModule.release()` | stops timers, publishes no live state, clears motion listeners, releases the link |
| `MotoHubModule.capability(type)` | returns `this` when the host asks for an implemented capability |
| `ModuleFeatures.features()` | registers the diagnostics screen under `Modules` |
| `ModuleLiveStatus.live` | `StateFlow<ModuleLiveState?>` for the Ride pill |
| `ModuleMotionSource` | `addMotionListener` / `removeMotionListener` for contract 43 |
| `ModuleMotionListener.onMotion(sample)` | host callback invoked once per decoded frame |
| `ModuleManifest` | id, version, contract, entry class, display name, description |
| `ModuleFeature` | id `wt9011-diagnostics`, title, description, placement, `screen` composable |
| `ModuleLiveState(label, featureId)` | pill text plus the feature it belongs to |
| `host.context` | Bluetooth manager and permission checks |
| `host.storage` | private directory for zero, mount and setup properties |
| `host.log` | `ModuleLogSink`; every notable event is mirrored as `wt9011: ...` |
| `host.ui` | Compose DSL: `Screen`, `Paragraph`, `SectionLabel`, `Fact`, `ActionRow` |

`Wt9011Module.manifest` duplicates the Gradle values. They are two separate declarations and must be
kept in sync; the packaged `META-INF/motohub-module.json` mirrors the same metadata.

### 3.3 Packaging

The `.mhm` artifact is a zip:

```text
wt9011-0.3.0.mhm
├── module.jar
│   ├── classes.dex
│   └── META-INF/motohub-module.json
├── module.sig
└── module.pub
```

`motohub-module.json` contains the same six metadata fields as the manifest. `module.sig` and
`module.pub` are the module signature and public key produced by the packaging task. Installation is
through **MOTO-HUB → Modules → Install from a file** with developer mode enabled.

## 4. Source map

| File | Responsibility | Android types? |
| --- | --- | --- |
| `build.gradle.kts` | module metadata, SDK levels, compile-only dependencies | no |
| `src/main/AndroidManifest.xml` | empty manifest required by the Android library plugin | n/a |
| `Wt9011Entry.kt` | entry point, module lifecycle, features, live pill, motion-source facade, diagnostics Compose screen, formatting helpers | yes |
| `Wt9011Link.kt` | BLE scan/connect/subscribe, sensor pinning, frame dispatch, watchdog, register reads, setup sequencing, motion publication, persistence, log ring buffer, `Wt9011Snapshot` | yes |
| `Wt9011Packet.kt` | UUIDs, frame/register/command constants, axis and gyro constants, decoder, parser, mount rotation, zero, battery mapping, angle helpers | no |
| `Packet.kt` | the ride-model data class and angular rates | no |
| `src/test/java/.../Wt9011PacketTest.kt` | 32 JVM tests for the wire format, parser, registers, zero, all flat and on-edge mounts, directional peaks, angles, commands, UUID matching and angular rates | no |

The split is deliberate: `Wt9011Packet.kt` and `Packet.kt` contain no Android imports, so the whole
decoding and correction path runs in plain JVM unit tests. `Wt9011Link.kt` is the only place that
touches `BluetoothGatt`; `Wt9011Entry.kt` is the only place that touches Compose and the host UI.

## 5. Runtime data flow

```text
BLE notification (data characteristic, GATT callback thread)
        │
        │  onCharacteristicChanged → onBytes(value, nowNanos)
        ▼
Wt9011FrameParser.feed(chunk, nowMs)
        ├── 0x55 0x61 (20 bytes) ─► decodeSample
        │                              ├── Wt9011Raw  (ax, ay, az, wx, wy, wz, roll, pitch, yaw)
        │                              └── Packet     (axis/sign/gyro constants applied)
        │                                     │
        └── 0x55 0x71 (20 bytes) ─► RegisterReply (battery, temperature, output content)

latest Packet + latest Wt9011Raw are stored in Wt9011Link
        │
        ├── snapshot() (10 Hz screen / 2 Hz pill)
        │       │
        │       ▼
        │   MountPosition.apply ──► Zero.apply (if captured) ──► ride Packet
        │       ├── screen: Ride section, raw section, diagnostics
        │       └── pill: "Lean 12° right · 0.08 g"
        │
        └── publish() (every frame, if the host has listeners)
                │
                ▼
            MountPosition.apply ──► Zero.apply ──► ModuleMotionSample
                │
                ▼
            ModuleMotionListener.onMotion (host records the trip and draws the Dashboard)
```

The important ordering is:

1. **Decode** applies only the compile-time axis/sign constants (`Wt9011Axes`). The resulting
   `Packet` is the default-frame model: long axis forward, printed +X right, label up.
2. **Mount** applies the rider's twelve-way mount. Flat: long axis front/right/back/left and label
   up/down, with the horizontal axis swap, the face-down vertical and yaw-rate signs, and the
   heading offset. On edge: the label face front/right/back/left with printed +Y up; the firmware's
   vertical installation supplies angles, accelerometer and gyro together in a virtual flat frame.
3. **Zero** subtracts the captured lean/pitch offsets, rotates the g vector, and levels the angular
   rate vector. It is captured after the mount and stored with it; changing the mount clears it.
4. **Snapshot** exposes the result as `ride`, alongside the untouched `raw` channels.
5. **Motion publication** converts the same `ride` packet to contract-43 axes and timestamps and
   delivers it to every listener. There is no module-side recording any more.

The motion sample conversion is:

| `ModuleMotionSample` field | From `Packet` | Notes |
| --- | --- | --- |
| `elapsedRealtimeNanos` | notification arrival | `SystemClock.elapsedRealtimeNanos()` |
| `leanDegrees` | `leanDeg` | + = right |
| `pitchDegrees` | `pitchDeg` | + = nose up |
| `forwardG` | `longG` | gravity included |
| `leftG` | `-latG` | packet is right-positive; contract is left-positive |
| `upG` | `vertG` | +1 g upright at rest |
| `rollRateRadiansPerSecond` | `rollRate` | + = leaning further right |
| `pitchRateRadiansPerSecond` | `pitchRate` | + = nose down (contract convention) |
| `yawRateRadiansPerSecond` | `yawRate` | + = turning left |
| `magneticHeadingDegrees` | `bearingDeg(yawDeg)` | 0…360, magnetic |
| `zeroed` | `zero != null` | true once a zero has been captured |

## 6. Components

### 6.1 `Wt9011Entry` and `Wt9011Module`

`Wt9011Entry` is the one-line entry point:

```kotlin
class Wt9011Entry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = Wt9011Module(host)
}
```

`Wt9011Module` implements `MotoHubModule`, `ModuleFeatures`, `ModuleLiveStatus` and
`ModuleMotionSource`. It owns:

- the `Wt9011Link` instance, created with `host.context`, `host.storage` and `host.log`;
- a `Handler` on the main looper;
- a `MutableStateFlow<ModuleLiveState?>` for the pill;
- a 500 ms pill ticker;
- the single `ModuleFeature` with the diagnostics screen;
- the motion-source facade, which forwards listener registration to the link.

Initialisation posts `link.resume()` and the first pill tick. `resume()` reconnects only to a
sensor that is already pinned, and only while `autoConnect` is on; it never pins a new sensor. The
first pin needs the rider's **Connect** tap, which calls `connect()`. A manual `disconnect()` is
remembered until the next **Connect** (MOTO-HUB-FIX 2026-11-22, fix 1; see
[`MAINTAINER_FIXES.md`](MAINTAINER_FIXES.md)). `release()` removes the ticker, clears the live state, marks the link released
so a scheduled scan cannot reopen it, clears the link's motion listeners, and shuts the link down
without changing the remembered-sensor or auto-connect settings.

The module does not draw on the Ride page beyond the pill. Contract 43 gives it the Ride Dashboard
through the host's own motion pipeline, so the module has no dashboard composable of its own.

#### Pill

`publishPill()` runs every 500 ms:

```kotlin
val fresh = snapshot.connected && snapshot.frameAgeMs in 0..PILL_STALE_MS
val label = if (fresh && ride != null) {
    "Lean %.0f° %s · %.2f g".format(abs(ride.leanDeg), side, ride.longG)
} else null
```

- `side` is `"right"` when `ride.leanDeg >= 0`, otherwise `"left"`.
- The label includes the absolute lean, the side, and longitudinal g.
- `PILL_STALE_MS = 1000`: no frame for a second hides the pill before the link watchdog (2 s)
  tears the link down.
- The `StateFlow` is updated only when the label changes, so a steady reading does not emit every
  500 ms.
- The label is published with `FEATURE_DIAGNOSTICS`, the feature id of the diagnostics screen.

#### Diagnostics screen

The screen is a `@Composable` registered as `ModuleFeature.screen`. It keeps a `snapshot` state and
refreshes it every 100 ms in a `LaunchedEffect` loop. The sensor streams at 100 Hz, so most frames
are deliberately not drawn; a bench readout does not need more, and it keeps the phone's battery
cost down.

Sections, in order:

| Section | Contents |
| --- | --- |
| Header strip | green link dot, `WT9011 IMU`, sensor name \| Hz • ms, CONNECT and ZERO buttons, calibration line |
| Lean card | −90°…+90° roll arc, peak markers, live pointer, artificial horizon, large lean reading, MAX L / PITCH / MAX R |
| G-force card | ±2.0 g lateral / ±2.5 g longitudinal plot, glowing dot, 2×2 peaks, RESET PEAKS |
| Mounting | flat label-up row and on-edge row (no label-down tiles), selected mount |
| Advanced | Ride facts, Link, Power and health, Recent, Clear zero, Send set-up |

The **Forget this sensor** row appears only when an address is remembered. It drops the pin,
clears the zero, resets the `configured` flag and peaks, and turns `autoConnect` off. The rider taps
**Connect** for the new sensor (MOTO-HUB-FIX 2026-11-22, fix 1).

Every `Fact` carries a `technical` flag; the module marks raw channels, counters, battery raw and
temperature as technical, and keeps rider-facing values non-technical.

#### Formatting helpers

- `degrees(value, decimals)` → `+12.3°`
- `gForces(value)` → `+0.08 g`
- `heading(yawDeg)` → `123° magnetic`
- `ageText(ageMs)` → `—`, `345 ms ago`, or `1.2 s ago`

All formatting uses `Locale.ROOT` so a device locale cannot change the decimal separator in a
diagnostic reading.

### 6.2 `Wt9011Link`

`Wt9011Link` is the BLE central and the state owner. It is constructed once per module and lives
until `release()`.

#### Observable state

The screen reads these `@Volatile` fields through `snapshot()`:

| Field | Meaning |
| --- | --- |
| `status` | human-readable link state |
| `connected` | GATT subscription is up and frames are expected |
| `deviceLabel` | advertised name of the selected device |
| `latest` | last decoded `Packet` in the default frame, before mount and zero |
| `raw` | last decoded raw channels |
| `zero` | captured zero or null, valid for the current mount |
| `mountPosition` | plane (flat/on-edge), yaw, and face (flat only) |
| `packetsPerSecond` | measured frame rate over a ~1 s window |
| `received` | cumulative decoded frames since process start |
| `lastFrameAt` | `SystemClock.elapsedRealtime()` of the last sample |
| `batteryPercent`, `batteryRaw` | battery register and its percentage |
| `temperatureC` | chip temperature |
| `outputContent` | last read `0x96` value |
| `configured` | the full setup block has completed and been stored |
| `displacementSuspected` | all axes have been near zero for over 2 s |
| `autoConnect` | reconnect automatically after a drop |
| `pinnedAddress` | the remembered sensor's BLE address, or null before the first connection |

`snapshot()` reads the fields once and returns an immutable `Wt9011Snapshot`. It computes the ride
model with `rideOf()`:

```kotlin
private fun rideOf(packet: Packet): Packet =
    zero?.apply(mountPosition.apply(packet)) ?: mountPosition.apply(packet)
```

The mount runs first, producing the bike frame; the zero then levels that frame. The zero is
captured from the already-mounted packet and persisted with the mount tag, so changing the mount
clears it. `snapshot()` computes `frameAgeMs` from `lastFrameAt`, or from `subscribedAt` before the
first frame, or `-1` when neither exists. `recentLines()` returns the event ring buffer under its
own lock.

#### Public actions

| Action | Effect |
| --- | --- |
| `addMotionListener(listener)` | adds a contract-43 listener, thread-safe, no duplicate |
| `removeMotionListener(listener)` | removes it |
| `connect()` | sets `autoConnect = true`, saves it, and starts a scan if idle |
| `reconnect()` | drops the link and immediately scans again, resetting the attempt counter |
| `forgetSensor()` | clears the pin, `configured`, zero and peaks, then starts a new scan and returns a message |
| `disconnect()` | sets `autoConnect = false`, saves it, and shuts the link down |
| `captureZero()` | requires a live link and a fresh sample, mounts and validates it, stores and persists a `Zero`, returns a message |
| `clearZero()` | drops the zero and deletes `zero.properties` |
| `setMountPosition(position)` | stores and persists the mount; clears a captured zero and returns true if there was one |
| `configureSensor()` | requires a live link, resets the auto-fix counter, sends the full setup block |
| `refreshDiagnostics()` | asks for battery and temperature now instead of waiting for the 30 s poll |
| `release()` | marks the link released, clears motion listeners and shuts it down without changing `autoConnect` |

`connect()` is deliberately a no-op while connected; the screen calls `reconnect()` in that state.
`reconnect()` is the fix for 0.1.0's dead Reconnect action.

#### Storage files

`Wt9011Link` writes three properties files into `host.storage` and loads them in `init`:

| File | Keys | Written when |
| --- | --- | --- |
| `zero.properties` | `model`, `mount`, `leanDeg`, `pitchDeg`, `forwardG`, `lateralG`, `upG`, `takenAt` | zero captured |
| `mounting.properties` | `model`, `yaw`, `face` | mount changed |
| `setup.properties` | `configured`, `autoConnect`, `deviceAddress` | connect/disconnect, setup completion, first pin, forget |

Reads tolerate corruption: a malformed or half-written file returns `null` and the module falls back
to defaults (no zero, `MountPosition.DEFAULT`, `configured = false`, `autoConnect = true`, no pin).
Writes catch exceptions and add a `"could not write ..."` event rather than crashing.

The zero is stored in the **mounted bike frame** and tagged with the mount that produced it. A model
or mount mismatch drops the file on the next start, which also covers the 0.2.0 zero captured in the
old candidate-B frame. It is cleared when the rider changes the mount or forgets a sensor.

#### Event log

`note(line)` prepends `HH:mm:ss`, appends to an eight-entry ring buffer (`RECENT_LINES = 8`), and
mirrors the line to `host.log` as `wt9011: <line>`. The diagnostics screen's "Recent" section joins
the ring buffer. This is not the frame stream: 100 frames per second would make it useless.

Events include scan start/timeout, subscription, sensor pinning, link loss, setup completion or
incompletion, output-content repair, zero capture/clear, mount changes, sensor forgetting,
register timeouts, displacement suspicion, and storage write failures.

### 6.3 `Wt9011Packet` (pure JVM layer)

This file is the protocol and correction toolbox. It contains:

| Symbol | Role |
| --- | --- |
| `Wt9011Protocol` | UUIDs, `matchesShort`, frame/register/command constants and builders, accel/gyro/angle scales |
| `Wt9011Axes` | the empirical sign/offset/gyro constants for the documented default frame |
| `Wt9011Raw` | one frame's raw channels, including `wx`, `wy`, `wz` in °/s |
| `Wt9011Sample` | raw channels plus the decoded `Packet` |
| `RegisterReply` | base register plus eight-register payload; `short`, `temperatureC`, `batteryPercent` |
| `batteryPercentOf` | voltage-to-percentage interpolation |
| `MountYaw`, `MountFace`, `MountPlane`, `MountPosition` | the twelve orientations and the frame rotation |
| `Zero` | captured tilt and g vector; `of` and `apply`, including angular-rate levelling |
| `zeroRefusal` | human-readable reason a sample cannot be a zero |
| `Wt9011Frame` | sealed dispatch: `Sample` or `Registers` |
| `decodeSample`, `decodePacket` | 20-byte data frame to `Wt9011Sample`/`Packet` |
| `wrap180`, `bearingDeg` | signed angle and compass bearing helpers |
| `Wt9011FrameParser` | accumulate, resynchronise, dispatch, count sequence |
| `readInt16` | signed little-endian 16-bit reader |

The details are in [`PROTOCOL.md`](PROTOCOL.md). The key architectural point is that none of this
file imports Android, so all of it is testable on the JVM.

### 6.4 `Packet`

`Packet` is the ride model the rest of the module uses:

```kotlin
data class Packet(
    val seq: Int,
    val sensorMillis: Long,
    val leanDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
    val longG: Float,
    val latG: Float,
    val vertG: Float,
    val rollRate: Float = 0f,
    val pitchRate: Float = 0f,
    val yawRate: Float = 0f
)
```

The 0.1.0 motion-sensor fields (`version`, `alert`, `sensorOk`, `watchingFall`) are gone: they never
described a WT9011 frame. `seq` comes from the parser, and `sensorMillis` is the **arrival** time on
the phone (`SystemClock.elapsedRealtime()`), not a device timestamp. The angular rates are radians
per second in the motorcycle frame.

`Packet` is the ride model the rest of the module uses. The fields are always in the bike frame;
`MountPosition` performs the rotation from the default sensor frame before any zero is applied:

- a quarter turn swaps or negates lean/nose, longitudinal/lateral g, and roll/pitch rate;
- label down negates vertical g and yaw rate, and mirrors the heading;
- the mount's yaw contributes −90° per step to the heading, or `180° − 90°·step` when face down;
- an on-edge mount uses the firmware's virtual flat frame for the whole packet (its +Y points
  opposite the label face, hence the 180° turn from label direction to virtual yaw); the same flat
  quarter-turn mapping, including vertical g, applies to angles, accelerometer and gyro.

The full flat table is in [`PROTOCOL.md`](PROTOCOL.md) §6.4 and the on-edge table in §6.5.

## 7. Threading model

| Work | Thread | Shared state |
| --- | --- | --- |
| `Wt9011Module` init, pill ticker, `release` | main looper | `liveState` |
| `Wt9011Link` public actions, scan control, GATT open/close, setup sequencing, watchdog, diagnostics poll, register retry timers | main looper | `status`, `connected`, `gatt`, timers |
| `BluetoothGattCallback` connection/service/descriptor events | binder thread, immediately posted to main | none directly |
| `onCharacteristicChanged` → `parser.feed` → `onSample` / `onRegisterReply` → `publish()` | GATT callback thread | parser, `latest`, `raw`, counters, motion listeners |
| Compose screen read loop | main looper | `snapshot()` |

Rules the code follows:

- **The parser is confined to the GATT callback thread.** It is not thread-safe and must not be fed
  from anywhere else. `onSubscribed()` calls `parser.reset()` when notifications are first enabled,
  so no partial frame from before the subscription can combine with the new stream.
- **UI state is `@Volatile`.** `status`, `connected`, `latest`, `raw`, `zero`, `mountPosition`,
  counters and the diagnostic values can be read from the main thread while the GATT thread writes
  them. Each field is individually safe to read; a snapshot is not a lock, so a frame arriving
  during `snapshot()` can mix adjacent frames' fields. The values are self-consistent objects and
  the screen redraws at 10 Hz, so this is a display-freshness detail, not a correctness issue.
- **`recent` is guarded by `synchronized(recent)`.** `note()` and `recentLines()` both use it.
- **Motion listeners are a `CopyOnWriteArrayList`.** Registration can happen from the host's thread
  while the GATT thread iterates. Each listener call is wrapped in `try/catch (Throwable)` so a host
  failure cannot kill the Bluetooth callback.
- **`publish()` runs on the GATT callback thread**, because the sample's arrival timestamp and the
  decoded packet are already there. The contract allows this, but a listener that blocks or does
  heavy work delays BLE callbacks and can cost frames; the host's listener is expected to return
  quickly.
- **Register retries and setup gaps use main-looper `postDelayed`.** `awaitingRegister` and
  `awaitingTries` are `@Volatile` because the GATT thread clears them when a reply arrives.
- **`connected` gates every BLE operation.** Permission-denied and adapter-off paths are reported
  in `status`, not thrown.

Two small concurrency caveats remain: the shared `SimpleDateFormat` and the single
`awaitingRegister` slot. See the review findings.

## 8. BLE lifecycle and timers

The link has no explicit state enum. It uses three booleans (`scanning`, `connecting`, `connected`)
plus the status string, and a pin (`pinnedAddress`). The effective state machine is:

```text
                 module created → connect() (scan starts)
       ┌──────────────────────────────────────────────┐
       ▼                                              │
    Scanning ── 15 s timeout, autoConnect ────────────┤ (2 s later while attempts < 3,
       │ WT* advertisement (or pinned address)        │  then every 30 s, forever)
       ▼                                              │
    Connecting ── 15 s timeout ──► shutDown ──► retryLater
       │ GATT connected
       ▼
    Discovering ── failure ──► shutDown ──► retryLater
       │ service + data/command characteristics + CCCD
       ▼
    Subscribing ── failure ──► shutDown ──► retryLater
       │ CCCD write success
       ▼
    Listening (watchdog every 250 ms)
       │ no frames for 2 s
       ├──────────────────────────► shutDown ──► retryLater
       │ disconnect() / release()
       ▼
    Idle / Stopped
```

`forgetSensor()` clears the pin and stops in **Idle / Stopped** until the rider taps **Connect**.

`disconnect()` and `release()` both call `shutDown()`, but only `disconnect()` sets
`autoConnect = false`, which is saved and kept across module loads. `release()` marks the link
released so a delayed retry or a late scan result cannot reopen it. `forgetSensor()` sets
`autoConnect = false` and does not scan (MOTO-HUB-FIX 2026-11-22, fixes 1 and 4).

### 8.1 Timing and limits

| Constant | Value | Used for |
| --- | --- | --- |
| `SCAN_TIMEOUT_MS` | 15,000 ms | one scan attempt |
| `CONNECT_TIMEOUT_MS` | 15,000 ms | GATT connection |
| `MAX_SCAN_ATTEMPTS` | 3 | fast retry burst before backing off |
| `RECONNECT_DELAY_MS` | 2,000 ms | delay before a retry, and rate limit for `retryLater` |
| `IDLE_RETRY_MS` | 30,000 ms | retry interval after the fast burst |
| `WATCHDOG_MS` | 250 ms | liveness check period |
| `STALE_TEARDOWN_MS` | 2,000 ms | no-frame threshold |
| `WRITE_GAP_MS` | 150 ms | gap between settings writes |
| `MTU_DELAY_MS` | 700 ms | delay before the ATT MTU request, after setup/read |
| `REGISTER_TIMEOUT_MS` | 200 ms | one register-read attempt |
| `REGISTER_TRIES` | 3 | register-read attempts |
| `DIAGNOSTICS_PERIOD_MS` | 30,000 ms | battery/temperature poll |
| `DISPLACEMENT_MS` | 2,000 ms | low-gravity window before flagging displacement mode |
| `RECENT_LINES` | 8 | event ring buffer |
| `RENDER_PERIOD_MS` | 100 ms | screen snapshot refresh |
| `PILL_PERIOD_MS` | 500 ms | pill refresh |
| `PILL_STALE_MS` | 1,000 ms | pill hides after this frame age |

### 8.2 Scanning, pinning and connecting

- `beginScan()` checks adapter, permissions (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`), Bluetooth
  enabled state and the BLE scanner before starting.
- The first three attempts use `SCAN_MODE_LOW_LATENCY`; after that the scan switches to
  `SCAN_MODE_BALANCED` and retries every 30 s, indefinitely, while `autoConnect` is true.
- If the adapter, permission or Bluetooth-enabled check fails, `retryWhenReady()` schedules the same
  `beginScan()` every 30 s while `autoConnect` is true, so turning Bluetooth or granting permission
  does not require a manual Connect.
- The scan uses **no filters**. A name filter at scan time would hide a unit whose advertisement is
  minimal; the selection is made in `onScanResult` instead.
- After the setup block and the first register read have gone out, `onSubscribed` schedules a
  247-byte ATT MTU request (`MTU_DELAY_MS`). It is deliberately not requested before
  `discoverServices()` (that suppresses `onServicesDiscovered` on some Android stacks) or alongside
  the setup writes (the first write can fail). The MTU is best-effort; at 100 Hz it lets several
  20-byte frames share one notification. The parser already handles multi-frame notifications.
- Before a sensor is pinned, the first device whose name starts with `WT` wins. Devices that turn
  out not to expose the WT service are added to `rejected` and skipped from then on, until the
  rider forgets the sensor or the module process restarts.
- Once `pinnedAddress` is set, **only that address is accepted**, even if its advertisement has no
  name and even if another WT device is closer. This is the fix for joining another rider's sensor.
- `onScanFailed` clears the scanning flag and reports the error code.
- `connectGatt` uses `TRANSPORT_LE`. A `SecurityException` is reported as a status message.
- `onConnected` requests `CONNECTION_PRIORITY_HIGH` (a hint; failure is ignored) and starts service
  discovery.

### 8.3 Service discovery and subscription

1. Find the documented `ffe5` service by short UUID, else the fallback service by exact UUID.
2. If the documented service is present, look up the data and command characteristics **inside it**
   by short UUID.
3. If only the fallback service is present, look up the two fallback characteristics by exact UUID
   inside it.
4. Require both characteristics and a CCCD on the data characteristic.
5. Call `setCharacteristicNotification(data, true)` and write `ENABLE_NOTIFICATION_VALUE` to the
   CCCD.
6. `onDescriptorWrite` promotes the link to `connected = true` only when the descriptor write
   succeeds and belongs to the current GATT.

Each failure path calls `shutDown(message)` and `retryLater()`.

### 8.4 Subscription completion

`onSubscribed()`:

- sets `connected = true`, `connecting = false`;
- calls `parser.reset()`;
- pins the device address on the first successful subscription and saves it;
- records `subscribedAt`, clears `lastFrameAt`, resets `scanAttempts` and `outputContentFixes`;
- sets status to `"Listening"`;
- starts the watchdog;
- if `configured` is false, runs the full setup block, then reads `0x96`;
- if `configured` is true, reads `0x96` and schedules the 30 s diagnostics poll.

The sensor starts streaming on its own once notifications are enabled. The module never sends a
"start streaming" command.

### 8.5 Teardown and retry

`shutDown(message)` removes the watchdog, diagnostics poll, connect timeout and scan timeout, stops
any scan, clears the connection flags, clears `awaitingRegister`, resets the frame rate and
displacement state, disconnects and closes the GATT, and sets the status message.

It deliberately leaves `latest`, `raw`, `lastFrameAt` and `pinnedAddress` in place, so the screen can
show the last reading and its age after a drop, and so the next connection targets the same sensor.
The pill hides because `connected` is false.

`retryLater()` starts a new scan after `RECONNECT_DELAY_MS`, but only when `autoConnect` is true and
at most once per 2 s. If that rate limit suppresses the retry entirely, no timer remains; see the
review notes. The scan-timeout path does not have this limitation: it keeps retrying every 2 s
until three attempts have been made, then every 30 s, so an unplugged sensor is eventually found
again.

## 9. Health monitoring

There is no per-frame acknowledgement, checksum or sequence number to lean on, so health is inferred
from arrival:

| Signal | Detection | Response |
| --- | --- | --- |
| Dead link | no frame for 2 s, checked every 250 ms | teardown, status `"No data for N s."`, retry if autoConnect |
| Displacement mode | all three g axes below 0.5 g in magnitude for 2 s | `displacementSuspected = true`, warning on screen, log event |
| Wrong output content | `0x96` read returns non-zero | one automatic unlock/output/save repair per connection, then re-read |
| Missing register reply | no reply covering the register after 3 × 200 ms | log `"no reply for register 0xNN"`, leave the last value |
| Phone cannot keep up | notifications are dropped by the stack | frames are lost; the sensor keeps its configured rate |
| Slow motion listener | host callback takes too long on the GATT thread | BLE callbacks are delayed and frames may be dropped; the module cannot decouple it without a queue |

`packetsPerSecond` is measured in a ~1 s window on the GATT thread. `received` is cumulative for the
process and is not reset by reconnects.

The displacement flag is latched until `shutDown()`; a successful on-the-fly repair leaves the
warning visible until the link is re-established. See the review notes.

## 10. Persistence

All state that must survive a process restart is written as Java `Properties` files in
`host.storage`:

```properties
# zero.properties
model=2
mount=FRONT/UP
leanDeg=4.0
pitchDeg=-1.0
forwardG=0.07
lateralG=0.0
upG=0.997
takenAt=1760000000000

# mounting.properties
model=2
yaw=FRONT
face=UP

# setup.properties
setupModel=2
configured=true
autoConnect=true
deviceAddress=AA:BB:CC:DD:EE:FF
```

- `loadStoredState()` runs in `Wt9011Link.init`, before the screen can draw, so the mount, zero and
  remembered sensor are already in force when the first snapshot is read.
- A zero is only accepted if **all eight** keys parse and its `mount` tag matches the current mount;
  otherwise it is ignored and deleted. The `model` tag also retires the 0.2.0 zero, which was
  captured in the old candidate-B frame.
- `clearZero()` deletes the file rather than writing zeroes; `setMountPosition()` does the same when
  the mount actually changes.
- `configured` now means "the whole setup block went out and was stored". `writeSequence` reports
  completeness, and `runSetupBlock` only sets the flag when every write succeeded. This fixes the
  0.1.0 behaviour where a failed block could still be recorded as complete.
- `setupModel` is bumped when the setup recipe changes. A file without it (or with an older value)
  is treated as unconfigured, so the 100 Hz / 256 Hz recipe runs on the next connection. This is how
  an upgrade from the 20 Hz / 42 Hz build reaches the sensor without a manual Send set-up.
- `autoConnect` is a session flag. `connect()`/`reconnect()` force it true; `disconnect()` forces
  it false; `forgetSensor()` keeps it true and starts a scan; `release()` leaves it alone. Module
  startup calls `connect()` unconditionally, so the module always scans again after a reload.
- `deviceAddress` is written on the first successful subscription and removed when the rider
  forgets the sensor. With no address, startup scans for the first WT sensor.

The zero's `takenAt` is wall-clock epoch milliseconds (`System.currentTimeMillis()`), while packet
`sensorMillis` is monotonic time since boot (`SystemClock.elapsedRealtime()`), and the motion sample
uses `elapsedRealtimeNanos()`. They are different clocks and should not be compared.

## 11. Diagnostics screen details

The screen is a rider dashboard with the bench tools folded into Advanced. It is split so a failure
can be attributed to one layer:

| Layer | What it proves |
| --- | --- |
| Header strip | which sensor is detected, how fresh the last frame is, the frame rate, and that Connect/Zero work |
| Lean card | lean and nose after mount and zero, read as a horizon and a −90°…+90° roll arc |
| G-force card | the live g vector and the directional peak loads |
| Mounting | the selected tile, and whether a zero is in force |
| Advanced | the raw bench state: Ride facts, Link, Power and health, Recent |

The 100 ms refresh is a snapshot poll, not a `Flow` collection. The screen does not own any link
state; closing it does not stop the link, the pill or the motion listeners.

## 12. Motion-source integration (contract 43)

`ModuleMotionSource` is the module's second output path, alongside the diagnostics screen and the
pill. It is the reason the module requires contract 43.

- `Wt9011Module.addMotionListener()` and `removeMotionListener()` delegate to the link.
- The link keeps a `CopyOnWriteArrayList<ModuleMotionListener>`.
- `onSample()` calls `publish(rideOf(packet), nowNanos)` only when listeners exist, so the
  correction math is skipped when nobody is listening.
- `publish()` builds a `ModuleMotionSample` in the contract's right-handed frame (x forward, y left,
  z up), converts the heading to a 0…360 bearing, sets `zeroed`, and catches every listener
  exception.
- Samples are delivered at the sensor's configured 100 Hz, with the notification arrival timestamp.
  At 50–200 Hz the sensor may batch several frames into one notification; those frames share the
  arrival timestamp because the device sends no per-sample timestamp.
- When the module is released or the sensor stops, the host falls back to the phone's own sensors by
  itself. The module does not send a "stream stopped" message; the absence of samples is the signal.

The module does **not** write a trip file itself. The host owns the trip, the sensor log and the
Dashboard; this keeps one recording path for phone and bike sensors and removes the CSV recorder,
its keep-alive and its I/O failure modes from the module.

## 13. Design decisions and trade-offs

| Decision | Why |
| --- | --- |
| Phone is the BLE central, no ESP32 bridge | one less board, one less power source, one less pairing step |
| Pin the first sensor by address | a track full of WT devices must not hand the bike another rider's IMU |
| Always scan at module creation | the rider wants the IMU up without a manual Connect; the first WT sensor found is pinned, and later connections only use that unit |
| Direct decode of the WT9011 stream | no dependency on the vendor app or its cloud |
| Empirical axis constants + bench tests | the vendor apps disagree about forward, so the documented default frame is fixed in code and the signs are settled on the bike |
| Zero as an angle offset, not an angle rotation | a rotation would cancel the real lean as well as the mounting error |
| G vector and angular rate rotated by minimum angle | removes roll and base-level error without changing gravity's magnitude or the turn rate |
| Mount as twelve persisted quarter turns | bracket orientation is a fitting decision, and the exact 90° permutations need no runtime matrix; the on-edge family uses the firmware's vertical mode, so the sensor is reconfigured rather than matrix-multiplied |
| Mount before zero, zero tagged with the mount | face-down changes which way the sensor's Z points; the zero is only meaningful in the bike frame that produced it |
| Heading from 9-axis fusion, never raw magnetometer | fusion is better than a two-axis `atan2`, and declination is out of scope |
| One-time flash-backed setup | the device keeps it; repeating it on every connection would cost a second of silence |
| Setup counted only when every write went out | never record a half-applied configuration as complete |
| Output content verified after setup | never pretend the configuration took |
| `ModuleMotionSource` instead of a CSV recorder | the host can record real trips from module data, and phone/bike samples share one pipeline |
| Watchdog instead of an ACK | the protocol has no acknowledgement or heartbeat |
| Resync by dropping one byte | the only synchronisation available is the `0x55` header |
| Pure JVM protocol layer | the code that cannot be checked by reading is the code that must be unit tested |

## 14. Limits worth knowing

- **Heading is magnetic.** No declination correction, no true north. The sensor's magnetic
  calibration is only as good as the environment it was calibrated in.
- **No checksum or sequence number.** Corrupted frames can be accepted; the parser can resync but
  cannot validate.
- **No flow control.** A slow phone drops frames rather than slowing the sensor.
- **Magnetic mounting matters.** A ferrous bracket or nearby motor/phase cables distort the field;
  no software can fix that.
- **Zero is a bench action.** The total-g window rejects large acceleration, but a steady ~0.3-0.5 g
  acceleration can still pass as a plausible zero. Capture it stationary.
- **The link stays up when the screen is closed**, by design, so the pill and the motion source keep
  working while riding. This costs a few milliamps and can be stopped with Disconnect.
- **The 20-byte GATT write limit** is respected: all commands are five bytes.
- **The mount model is quarter turns.** Eight flat orientations, plus four on-edge label directions
  with the printed +Y arrow up. An arbitrary angle in any plane, or an on-edge mount with +Y down,
  is unsupported: the angle model, the zero and the vendor's installation modes assume those exact
  poses.
- **Yaw offset is compile-time.** `YAW_OFFSET_DEG` is not a stored setting; the mount's own heading
  offset is automatic.
- **A host listener runs on the GATT thread.** A slow listener costs frames; the contract expects it
  to return quickly.

## 15. Review findings

These are static-reading observations against the 0.3.0 source, not runtime test results. Items
marked **Fixed** were present in an earlier version and are resolved in the current one; the rest
remain.

| # | Severity | Finding | Effect | Suggested fix |
| --- | --- | --- | --- | --- |
| — | Fixed | `captureZero()` accepted the last decoded sample without checking `connected` or the frame age. | A stale sample from before a link drop could be accepted as a zero. | Fixed: a zero now requires a live link and a frame age ≤ 1 s. |
| — | Fixed | `displacementSuspected` was latched until `shutDown()`. | A successful output-content repair left the warning visible. | Fixed: cleared as soon as gravity returns. |
| — | Fixed | `SimpleDateFormat clock` was shared and `note()` could run on main and GATT threads. | A log timestamp could be garbled. | Fixed: `note()` synchronises on `clock`. |
| 4 | Low | A single `awaitingRegister` slot is used for all reads. Battery and temperature polls can overlap (battery retries at 200 ms; temperature starts at 400 ms). | The second request can overwrite the first's pending state, abandoning its retries. | Track pending reads per register, or serialise the diagnostics reads. |
| — | Fixed | `retryLater()` was rate-limited to one call per 2 s and returned without scheduling if a drop happened inside that window. | A connection that established and dropped quickly could leave auto-connect idle with no timer. | Fixed: it always schedules, clamping the wait to the remaining window. |
| 6 | Info | Unused in production: `refreshDiagnostics()`, `decodePacket()`, `REG_ROLL/PITCH/YAW`, `REG_HX`, `REG_VERSION1/2`. `rate10Hz()` is unused in production but is exercised by a test. | No behavioural effect; extra surface to maintain. | Keep as documented reference/API, or remove. |
| 7 | Info | `YAW_OFFSET_DEG` is a compile-time constant. | A sensor heading bias needs a rebuild, unlike the zero and mount. | Consider a persisted heading-offset setting if riders need it. |
| — | Fixed | The on-edge mapping treated the vertical-mode accelerometer/gyro as physical sensor axes. | Gravity landed on `longG`, `vertG` read ≈ 0, and Zero refused with “vertical axis reads -0.01 g”. | Fixed: the whole packet is now mapped through the firmware's virtual flat frame; the +Y-up/USB-down mount zeroes normally. |
| — | Fixed | `Zero.of` accepted a negative vertical g and then could not level the g vector. | The zero is now captured after the mount, in a frame whose up is +Z, and is tagged with the mount that produced it. | — |
| — | Fixed | A replaced sensor inherited the previous unit's zero, and `forgetSensor()` left it in force. | `forgetSensor()` now clears the zero with the pin. | — |
| — | Fixed | The default decode used the wrong axis candidate. | 0.3.0 decodes the documented default (`+Y` forward, `+X` right), rotates for the other flat mounts, and adds the on-edge family. | — |
| — | Fixed | Recorder header missing its newline; recorded values pre-zero/pre-mounting. | The recorder was removed in 0.2.0; MOTO-HUB records corrected samples through contract 43. | — |
| — | Fixed | `Wt9011FrameParser.reset()` was never called. | `onSubscribed()` now calls it before the new stream starts. | — |
| — | Fixed | `configured = true` was set even when setup writes failed. | `writeSequence` reports completeness; the flag is only set for a whole block. | — |
| — | Fixed | Reconnect action called `connect()`, a no-op while connected. | `reconnect()` now tears down and scans; the UI calls it when connected. | — |
| — | Fixed | Recording I/O was not exception-safe and is now gone. | No module-side file writes on the frame path. | — |

## 16. Glossary

| Term | Meaning |
| --- | --- |
| **Ride model** | The `Packet` after mount and zero; what the screen's Ride section, the pill and the motion sample show. |
| **Raw channels** | The nine decoded sensor fields before `Wt9011Axes` or any correction. |
| **Zero** | A captured lean/pitch offset and g-vector orientation, subtracted/rotated from later frames. |
| **Mount** | The rider's `MountPosition`: long-axis yaw (front/right/back/left) and face (up/down). |
| **Pinning** | Remembering the first sensor's BLE address so no other WT device is used. |
| **Setup block** | The seven flash-backed commands sent once per install. |
| **Watchdog** | The 250 ms timer that tears the link down after 2 s without frames. |
| **Displacement mode** | A sensor output mode that reports no gravity; detected by all axes sitting near zero. |
| **CCCD** | Client Characteristic Configuration Descriptor; enables notifications. |
| **Contract 43** | The host API generation that adds `ModuleMotionSource`. |
| **Motion sample** | A `ModuleMotionSample` in contract axes: x forward, y left, z up, rates in rad/s. |
| **mhm** | MOTO-HUB module package: signed zip containing `module.jar`, `module.sig`, `module.pub`. |
