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
| Lean | Roll or Pitch, see `Wt9011Axes` | negative = left, positive = right |
| Pitch | the other of Roll/Pitch | nose up positive |
| Heading | Yaw (9-axis fusion) | **magnetic** bearing, 0–360° |
| Longitudinal / lateral / vertical g | AX, AY, AZ | 1 g on the vertical axis at rest |
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

- **Horizontal, Z up.** The module writes the horizontal install mode (`FF AA 23 00 00`) and never
  the vertical one. A few degrees of tilt are fine — that is what "Zero" is for — but the sensor's
  Z axis must point up, not forward or sideways.
- **Non-ferrous bracket only.** Aluminium, plastic or 3D-printed. Keep it away from the motor's
  magnets and phase cables, the steel frame and tank, the fork legs, speakers, phone magnets and
  the ignition coil. A ferrous bracket bends the magnetic field and the heading wanders with the
  throttle.
- **At least 20 cm from anything magnetic or electronic.** The controller is the worst offender:
  keep the harness a hand-width from it, and route the wires away from the phase cables.
- **Rigid.** A bracket that flexes reads as lean. The sensor must not move relative to the frame.

## First install: connect once, configure once

Nothing happens until the rider taps **Connect** the first time. That connection takes the first
sensor whose name starts with `WT` and **remembers its address**: from then on the module only
ever talks to that sensor, connects to it by itself, and never touches anything else - not
another rider's WitMotion at a meeting. **Forget this sensor** lets go of it, for a replaced unit.

The sensor keeps its settings across power cycles, so the setup block runs **once per install**,
on that first connection, and again from **Send set-up**. It counts as done only when every
write went out; a block cut short runs again on the next connection. It writes:

```
FF AA 69 88 B5   unlock
FF AA 96 00 00   output = acceleration + angular rate + angle
FF AA 03 07 00   20 Hz
FF AA 1F 03 00   42 Hz bandwidth
FF AA 24 00 00   9-axis algorithm
FF AA 23 00 00   horizontal install
FF AA 00 00 00   save
```

Writes are 150 ms apart and all land inside the sensor's 10 s configuration window after the
unlock. After the block, the module reads back `0x96` (the output-content register). If the sensor
ignored the writes, the register still reports displacement mode, the screen says so and the module
keeps working with whatever the sensor is sending — it never pretends the configuration took.

If the sensor is left in displacement mode, acceleration sits near zero while the bike is at rest
for a couple of seconds. The module notices that pattern and flags it rather than showing 0.00 g.

## Calibration checks: five bench tests

The axis mapping and the signs in `Wt9011Axes` are **empirical**. The datasheet's axis diagram
describes a left-handed triad and the vendor's own apps disagree about which way is forward, so
these values are settled on the bench, not by reading a PDF. All of them are constants: one flash
verifies all five tests.

| Test | Do this | Expect |
| --- | --- | --- |
| **T1** up axis and sign | bike upright, stand still | vertical g ≈ **+1.000 g** |
| **T2** lean channel and sign | lean the bike right, ~15–20° | lean goes **positive**, pitch barely moves |
| **T3** nose sign | lift the front wheel | pitch goes **positive**, lean barely moves |
| **T4** heading sense and offset | point the sensor's forward axis at magnetic north, then 90° right | heading ≈ **0°**, then ≈ **90°**; if it reads 180° the bracket faces backwards |
| **T5** g-force signs | upright at rest, then push the bike forward | vertical ≈ +1.0 g; pushing forward gives **positive** longitudinal g |
| **T6** yaw-rate sign | on the bench, before fitting, turn the sensor to the left by hand; or ride one left turn with a trip recording | a left turn reads a **positive** yaw rate (the yaw-rate column of the trip's sensor export) |

What to change when a test fails, all in `Wt9011Axes` in `Wt9011Packet.kt`:

- T1 fails: `ACC_SIGN = -1f`.
- T2 and T3 are swapped: `LEAN_IS_PITCH = true`. The two candidate axis conventions are one
  boolean apart; the screen shows every raw channel so the swap is visible immediately.
- T2 lean is the right channel but the wrong way: `LEAN_SIGN = -1f`.
- T3 nose is inverted: `PITCH_SIGN = -1f`.
- T4 runs backwards: `YAW_SIGN = -1f`. If it is consistently 180° out, that is a bracket mounted
  facing backwards — set `YAW_OFFSET_DEG = 180f` rather than flipping the rider.
- T5 longitudinal g is inverted: `ACC_SIGN = -1f` (the same switch as T1: gravity and inertia
  must agree).
- T6 a left turn reads negative: `GYRO_SIGN = -1f`.

`Mounting` (flip nose / flip side) covers the same bracket-is-backwards problem from the UI, for a
rider who does not want to reflash. It flips the angles and the matching g channel together and
never touches the heading or the vertical g, because those describe the world, not the bracket.

**Zero** captures the bracket's own tilt with the bike standing upright and subtracts it afterwards.
It is taken in the sensor's frame, before the two switches, so changing a switch does not spoil it.
It refuses a sample that is not a plausible zero (total gravity outside 0.7–1.3 g, or less than
0.5 g on the vertical axis) and explains why, so a zero taken while the bike is rolling cannot
quietly ruin every later reading. It is a plain angle offset, not a rotation: a rotation would cancel
the real lean along with the mounting error.

## Reading the diagnostics

The screen is split so that a failure can be attributed to one layer:

- **Link** — status, device name, packets per second, frame age. The readings keep their last frame
  but the age line says how old it is; the Ride pill hides after 1 s without a frame, and after 2 s
  the link is dropped and rescanned.
- **Ride** — the values after zero and mounting, i.e. what the rider sees.
- **Sensor, as it sends it** — raw channels, before any of the module's corrections.
- **Mounting**, **Power and health** — the switches in force, battery, temperature, output content.
- **Recent** — the last eight events, with timestamps.

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
./gradlew :modules:wt9011:testDebugUnitTest        # decoder, parser, zero, protocol tests
./gradlew :modules:wt9011:modulePackage            # writes build/module/out/wt9011-0.2.0.mhm
./gradlew :modules:wt9011:checkModuleLinkage \
    -PmotohubApk=/path/to/moto-hub.apk              # against a real app build
```

Install the `.mhm` from **MOTO-HUB → Modules → Install from a file**, with developer mode on.
It needs a MOTO-HUB that knows contract 43.

## Limits worth knowing

- The heading is only as good as the sensor's magnetic calibration. Do not calibrate next to a
  steel garage door or a car: the field there is too distorted to compare against. Rotate the sensor
  through all orientations well away from metal.
- The device caps a GATT write at 20 bytes and streams freely once notifications are on. There is no
  flow control, so a slow phone drops frames rather than the sensor slowing down.
- The link stays up when the screen is closed, so the Ride pill and the trip keep their data
  while riding. That costs a few milliamps; the link can be disconnected from the screen and the
  choice is remembered. Out of range, the module keeps looking: quickly at first, then every 30 s.
- Declination, true heading, lean projection onto the route and any riding HUD beyond the pill are
  out of scope for this module.
