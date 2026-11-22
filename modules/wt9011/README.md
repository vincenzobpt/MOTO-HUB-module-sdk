# WT9011 IMU

Lean angle, g-forces and magnetic heading for MOTO-HUB Solo, read straight from a
WitMotion **WT9011DCL** over Bluetooth LE. The module is the BLE central: it connects to the
sensor itself, so no ESP32, no phone motion sensors and no extra app are involved.

Written by [sur00t](https://github.com/sur00t), starting from MOTO-HUB's motion-sensor module.
AGPL-3.0, like the rest of MOTO-HUB's modules. Not in the catalogue yet: install it from a file.

## In MOTO-HUB

The module hands every sample to MOTO-HUB through `ModuleMotionSource` (contract 43). While the
sensor streams:

- a recorded trip takes lean, g-forces and yaw rate from it, and the phone's motion sensors are
  switched off (its barometer stays on for air pressure);
- the Ride Dashboard's lean, pitch, g-force and compass widgets show it.

When the sensor goes quiet for a second and a half the phone takes over again by itself, and
hands back when the sensor returns. Every record in the trip says which of the two it came from.
The module itself keeps nothing on the phone but its settings.

The phone's own sensors are a fallback only. A phone in a pocket cannot know the bike's lean, and
a phone in a cradle moves with the bars rather than the frame.

## What it shows

| Reading | Source | Notes |
| --- | --- | --- |
| Lean | Pitch field (default mount) | negative = left, positive = right |
| Pitch | Roll field (default mount) | nose up positive |
| Heading | Yaw (9-axis fusion) | **magnetic** bearing, 0–360° |
| Longitudinal / lateral / vertical g | AY / AX / AZ (default mount) | 1 g on the vertical axis at rest |
| Battery | register `0x64` | voltage bucket, shown with the raw value |
| Temperature | register `0x40` | chip temperature, not air temperature |

Everything is visible on the module's own screen (**Modules → WT9011 IMU**) at 10 Hz, and the
lean plus longitudinal g appear as a pill on the Ride page while frames are arriving. The angular
rates (`WX`, `WY`, `WZ`) are not shown; they go to MOTO-HUB for the yaw rate.

The heading is a *magnetic* bearing. The module never subtracts declination: there is no
declination value it could know, and hard-coding one would be wrong in most of the world. If the
app adds a GPS `GeomagneticField` correction, that is the app's business and its label; this module
reports what the sensor says. Heading is never derived from the raw magnetometer (`Hx`/`Hy`) —
the 9-axis fusion already does that better than a two-axis `atan2` would.

## Mounting

The bracket is the calibration, so it has to be right before the sensor is bolted down.

- **Flat, label either way up.** The sensor lies on its large face and the module is told which of
  the eight quarter-turn orientations it ended up in. The default is the documented one: label up,
  the long axis (printed **+Y**, the logo end) pointing at the front of the bike, printed **+X**
  pointing to the bike's right and printed **+Z** pointing up. With that mount, lean is the Pitch
  field, nose is Roll, forward g is AY and rightward g is AX. The module's Mounting section offers a
  label-up row and an on-edge row — four quarter-turns each — so the rider picks what is actually
  fitted instead of rebuilding the module. The label-down orientations remain supported by the
  model, but the rider screen does not offer them.
- **On edge, label face horizontal.** The sensor can also stand on its edge the way WitMotion
  documents its vertical installation: the printed **+Y** arrow points up, and the label face looks
  at the front, right, back or left of the bike. The module offers those four in a separate row and
  rewrites the sensor's installation direction to vertical when one is picked. The printed +Y arrow
  is the up reference; the USB connector is at the **−Y** end, so it points down when +Y is up.
  WitMotion's vertical mode reports angles, accelerometer and gyro in the same virtual flat frame,
  and the module maps that frame to the bike. WitMotion's manual also asks for a device calibration
  after switching to vertical; the module's **Zero** removes the residual bracket tilt in that
  frame, and the vendor app is the place for a full accelerometer calibration if the angles look
  biased.
- **Quarter turns only.** The flat options are front/right/back/left with the label up or down; the
  on-edge options are the four label-face directions with +Y up. An arbitrary angle in any plane is
  out of scope: the model maps exact 90° turns, with no interpolation and no full rotation matrix.
- **Non-ferrous bracket only.** Aluminium, plastic or 3D-printed. Keep it away from the motor's
  magnets and phase cables, the steel frame and tank, the fork legs, speakers, phone magnets and
  the ignition coil. A ferrous bracket bends the magnetic field and the heading wanders with the
  throttle.
- **At least 20 cm from anything magnetic or electronic.** The controller is the worst offender:
  keep the harness a hand-width from it, and route the wires away from the phase cables.
- **Rigid.** A bracket that flexes reads as lean. The sensor must not move relative to the frame.

## First install: automatic connect, configure once

The module starts scanning for a WT sensor as soon as it is loaded, and keeps retrying while it is
running. The first sensor whose name starts with `WT` is **remembered by address**: from then on the
module only ever talks to that sensor and never touches anything else - not another rider's
WitMotion at a meeting. Because the first scan is automatic, keep other WT units out of range on the
first install. **Forget this sensor** clears the pin and immediately scans for a replacement.
A manual **Disconnect** stops the search for the current session; the next time the module is
loaded it looks again.

The sensor keeps its settings across power cycles, so the setup block runs **once per install**
(on that first connection), again when the mounting direction changes between flat and on-edge, again
when the module's setup recipe changes (a stored `setupModel` invalidates the old flag), and from
**Send set-up**. It counts as done only when every write went out; a block cut short runs
again on the next connection. It writes:

```
FF AA 69 88 B5   unlock
FF AA 96 00 00   output = acceleration + angular rate + angle
FF AA 03 09 00   100 Hz
FF AA 1F 00 00   256 Hz bandwidth (at least twice the output rate)
FF AA 24 00 00   9-axis algorithm
FF AA 23 00 00   horizontal install (23 01 00 vertical, for an on-edge mount)
FF AA 00 00 00   save
```

Writes are 150 ms apart and all land inside the sensor's 10 s configuration window after the
unlock. After the block, the module reads back `0x96` (the output-content register). If the sensor
ignored the writes, the register still reports displacement mode, the screen says so and the module
keeps working with whatever the sensor is sending — it never pretends the configuration took.

If the sensor is left in displacement mode, acceleration sits near zero while the bike is at rest
for a couple of seconds. The module notices that pattern and flags it rather than showing 0.00 g.

## Calibration checks: six bench tests

The signs in `Wt9011Axes` are **empirical**, and the vendor's axis diagram and its two apps disagree
about which way is forward. The default mount follows the documented one: printed +Y forward,
printed +X right, label up. The rider-facing picker covers the other three label-up flat
orientations and the four on-edge ones; the constants cover the signs of that default frame. One
flash verifies all six tests.

| Test | Do this | Expect |
| --- | --- | --- |
| **T1** up axis and sign | bike upright, stand still | vertical g ≈ **+1.000 g** |
| **T2** lean channel and sign | lean the bike right, ~15–20° | lean goes **positive**, pitch barely moves |
| **T3** nose sign | lift the front wheel | pitch goes **positive**, lean barely moves |
| **T4** heading sense and offset | point the sensor's printed +Y at magnetic north, then 90° right | heading ≈ **0°**, then ≈ **90°**; if it reads 180° out, the long axis points backwards — pick **Back**, not a heading offset |
| **T5** g-force signs | upright at rest, then push the bike forward | vertical ≈ +1.0 g; pushing forward gives **positive** longitudinal g |
| **T6** yaw-rate sign | on the bench, before fitting, turn the sensor to the left by hand; or ride one left turn with a trip recording | a left turn reads a **positive** yaw rate (the yaw-rate column of the trip's sensor export) |

What to change when a test fails, all in `Wt9011Axes` in `Wt9011Packet.kt`:

- T1 fails: `ACC_SIGN = -1f`.
- T2 and T3 are swapped: the unit's printed axes disagree with the vendor diagram. Swap the
  Pitch/Roll terms in `decodeSample` and the WY/WX terms beside them, then rebuild. Do not flip
  `LEAN_SIGN` to compensate, or T5 goes with it.
- T2 lean is the right channel but the wrong way: `LEAN_SIGN = -1f`.
- T3 nose is inverted: `PITCH_SIGN = -1f`.
- T4 runs backwards: `YAW_SIGN = -1f`. A fixed few degrees of sensor heading bias is
  `YAW_OFFSET_DEG`; a bracket whose long axis points backwards is the **Back** tile.
- T5 longitudinal g is inverted: `ACC_SIGN = -1f` (the same switch as T1: gravity and inertia
  must agree).
- T6 a left turn reads negative: `GYRO_SIGN = -1f`.

**Mounting** is the rider's runtime correction. For a flat bracket: which way the printed +Y long
axis points (front/right/back/left) and which face is outward (label up/down). For an on-edge
bracket: which way the label face looks (front/right/back/left), with the printed +Y arrow up. It is
persisted in `mounting.properties` and the model exposes those twelve quarter-turn orientations;
the rider screen shows the four label-up flat options and the four on-edge ones. The heading offset
follows the mount: front 0°, right −90°, back −180°, left +90°, and a label-down sensor is mirrored
in heading and yaw rate because its measured Z axis points at the road. An on-edge mount uses the
same quarter turns in the firmware's virtual frame; angles, accelerometer and gyro are all in that
frame.

**Zero** captures the bracket's own tilt with the bike standing upright and subtracts it afterwards.
It is taken after the mount has been applied, in the bike frame the rider sees, and stored with the
mount that produced it. Changing the mount clears the zero, because a zero captured with the label
up is not valid with the label down; the screen says so and asks for another capture. Zero also
requires a live link and a fresh frame, and refuses a sample that is not plausible (total gravity
outside 0.7–1.3 g, or less than 0.5 g on the vertical axis). It explains why, so a zero taken while
the bike is rolling cannot quietly ruin every later reading. It is a plain angle offset, not a
rotation: a rotation would cancel the real lean along with the mounting error.

## Reading the screen

The screen is a dark rider dashboard; the bench facts live under **Advanced**:

- **Header strip** — green link dot, `WT9011 IMU`, and `sensor name | 19Hz • 44ms`; **CONNECT**
  (connect/reconnect) and **ZERO** (capture/replace the zero) sit on the right, with the calibration
  line underneath (`Zeroed at lean 0.1°, pitch -0.5°.`).
- **Lean card** — a full −90°…+90° roll arc with 15° ticks, peak-lean markers, a live pointer, an
  artificial horizon, the large amber lean reading (`28.4° R`), and **MAX L / PITCH / MAX R**.
- **G-force card** — a ±2.0 g lateral / ±2.5 g longitudinal plot with a glowing live dot, a 2×2 peak
  grid (**MAX L / MAX R / ACCEL / BRAKE**), and **RESET PEAKS**.
- **Mounting** — the label-up flat row and the on-edge row. Label-down is not offered on this screen.
- **Advanced** — ride facts, Link, Power and health, Recent, Clear zero and Send set-up.

There is no checksum, length or sequence number in the WT9011's frames, so a corrupted frame cannot
be detected as such. What the module can do, it does: a module-side counter, an arrival timestamp,
and a parser that resynchronises by dropping exactly one byte.

## Bluetooth permissions

Android 12+ (the app targets 14+) needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` granted to
MOTO-HUB. Without them the module reports that it cannot scan instead of failing silently. Location
permission is not needed for BLE scanning on this API level.

## UUIDs

The sensor advertises the WitMotion trio on the non-standard base `…00805f9a34fb`:

```
service 0000ffe5-0000-1000-8000-00805f9a34fb
data    0000ffe4-0000-1000-8000-00805f9a34fb   notifications
command 0000ffe9-0000-1000-8000-00805f9a34fb   write with response
```

Some units advertise the standard `…00805f9b34fb` base instead. These UUIDs are compared by their
16-bit part, so both bind. The custom `49535343-…` fallback pair is compared exactly — those two
UUIDs share their first four bytes, so a short comparison would confuse the data and command
characteristics.

## Building

The module is built from the SDK checkout, which needs a JDK 21 and an Android SDK with platform 36
(see [getting started](../../docs/getting-started.md)).

```sh
./gradlew :modules:wt9011:testDebugUnitTest        # decoder, parser, zero, mount and protocol tests
./gradlew :modules:wt9011:modulePackage \
    -Pmotohub.moduleKeyDir=.../scratch/motohub-keys # writes build/module/out/wt9011-0.3.0.mhm
./gradlew :modules:wt9011:checkModuleLinkage \
    -PmotohubApk=/path/to/moto-hub.apk              # against a real app build
```

Install the `.mhm` from **MOTO-HUB → Modules → Install from a file**, with developer mode on.
It needs a MOTO-HUB that knows contract 43.

## Developer documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — module contract, data flow, threading model, BLE
  lifecycle, persistence, motion-source publishing and diagnostics.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — WT9011 frames, registers, commands, axis mapping, parser
  and processing math, including the angular-rate path.
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) — build/test/package workflow, test inventory,
  extension guide, debugging checklist and review notes.

## Limits worth knowing

- The heading is only as good as the sensor's magnetic calibration. Do not calibrate next to a
  steel garage door or a car: the field there is too distorted to compare against. Rotate the sensor
  through all orientations well away from metal.
- The device caps a GATT write at 20 bytes and streams freely once notifications are on. There is no
  flow control, so a slow phone drops frames rather than the sensor slowing down.
- The link stays up when the screen is closed, so the Ride pill and the trip keep their data
  while riding. That costs a few milliamps; the link can be disconnected from the screen and the
  choice is remembered. Out of range, the module keeps looking: quickly at first, then every 30 s.
- The mount model covers flat quarter turns and the four on-edge label directions with the printed
  +Y arrow up. An arbitrary angle in any plane, or an on-edge mount with +Y down, would need a full
  rotation model; the current zero and install mode assume the vendor's documented poses.
- Declination, true heading, lean projection onto the route and any riding HUD beyond the pill are
  out of scope for this module.
