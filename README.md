# MOTO-HUB module SDK

Everything you need to write a **module** for [MOTO-HUB ADV-SOLO](https://github.com/vincenzobpt/MOTO-HUB-ADV-SOLO-releases), the Android app that connects
a phone to a motorcycle's TFT dashboard.

[![Discord](https://img.shields.io/badge/Discord-module%20ideas%20%26%20help-5865F2?logo=discord&logoColor=white)](https://discord.gg/FzhXZtPhC8)
[![Website](https://img.shields.io/badge/website-motohub.techub.eu-111111)](https://motohub.techub.eu)
[![Module catalogue](https://img.shields.io/badge/catalogue-MOTO--HUB--modules-e10600)](https://github.com/vincenzobpt/MOTO-HUB-modules)

A module is a single file (`.mhm`) that MOTO-HUB downloads or installs from storage and **loads
into its own process while it runs**. It can add pages to the app, fill the motorcycle's screen
(Android Auto is a module), put the Ride Dashboard on a third-party head unit, receive
turn-by-turn guidance and projected audio, turn the rider's rides into 3D films, and more. The app itself knows nothing about any
particular module: it asks for *capabilities* by interface and draws whatever the module offers.

## What is in this repository

| Path | What | Licence |
|---|---|---|
| [`module-api/`](module-api) | The contract every module is compiled against. The app ships these exact classes. | Apache-2.0 |
| [`build-logic/`](build-logic) | The `motohub.module` Gradle plugin: manifest, dex, signing, `.mhm`, linkage check. | Apache-2.0 |
| [`examples/hello-module/`](examples/hello-module) | The smallest useful module: one page, one button, a file in its own storage. Start here. | Apache-2.0 |
| [`modules/android-auto/`](modules/android-auto) | The complete source of the Android Auto module MOTO-HUB installs. | **AGPL-3.0** |
| [`modules/dashcam/`](modules/dashcam) | The complete source of the Dashcam module: a Wi-Fi dashcam on the phone and in the dashboard's map panel. | **AGPL-3.0** |
| [`modules/flyby-route-simulator/`](modules/flyby-route-simulator) | The complete source of the Flyby Route Simulator: plans a route and rides it virtually. The reference for rides, routing, places, maps and extensions; see [docs/example-route-simulator.md](docs/example-route-simulator.md). | **AGPL-3.0** |
| [`docs/`](docs) | The developer guide. | Apache-2.0 |

The contract and tooling are Apache-2.0 so that you can write a module under any licence you
like, including a closed one. The Android Auto module is AGPL-3.0 because it is derived from
[headunit-revived](https://github.com/andreknieriem/headunit-revived); its source is published
here to meet that licence, and each published version of it has a matching tag in this repository.
The Dashcam and Flyby Route Simulator modules are AGPL-3.0 by choice, and are tagged the same way.

## Quick start

Requirements: JDK 17 or 21, the Android SDK (platform 36, build-tools with `d8`), `adb`, and a
phone running **[ADV-SOLO](https://github.com/vincenzobpt/MOTO-HUB-ADV-SOLO-releases/releases/latest) 0.1.25 or later**.

```bash
git clone https://github.com/vincenzobpt/MOTO-HUB-module-sdk
cd MOTO-HUB-module-sdk
./gradlew :examples:hello-module:pushModule
```

Then on the phone: **Settings ▸ 9 Modules**, turn on **Developer mode** at the bottom, **Install from a file…**, pick
`hello-0.1.0.mhm` from Download. The module's card appears; **About** opens the page it brought.

The full walk-through, including how to start your own module, is in
[docs/getting-started.md](docs/getting-started.md).

## The developer guide

1. [Getting started](docs/getting-started.md): build, install and change the example
2. [The contract](docs/contract.md): how a module is loaded, the manifest, versioning and the rules
3. [Capabilities](docs/capabilities.md): everything a module can offer
4. [Rides, the 3D scene and the rider's AI](docs/rides-and-scene.md): what the host lends a module from contract 11, from contract 22 saving rides, routing, places, sights and a flat map, from 23 drawing a film on another computer, and from 26 sending a project to a computer to be edited there
5. [Borrowed libraries](docs/borrowed-libraries.md): what you may call, and the linkage check (**read this one**)
6. [Packaging and signing](docs/packaging-and-signing.md): the `.mhm` format, your developer key, developer mode
7. [Publishing](docs/publishing.md): getting a module into the in-app catalogue
8. [Troubleshooting](docs/troubleshooting.md)
9. [Example: the route simulator](docs/example-route-simulator.md): a module that makes rides, as a reference

## Status

The contract is at **version 26**. It only grows by appending, and the app still loads modules
built against contract 3 or later. Contracts 11 to 14 (rides, the 3D scene, ride and route
actions, the rider's AI) need ADV-SOLO 0.1.29, contract 15 (draft, real-time and H.265 video
exports) ADV-SOLO 0.1.30, contract 16 (credits in the film's credit roll) ADV-SOLO 0.1.31,
contracts 17 to 20 (the rider's figure in action, the Ultra and Real 3D finishes, projections
that fill only a dashboard panel, and their own map-source tiles), 21 (the Real 3D engine's
highest tier) and 22 (modules that make rides: saving simulated rides and routes, the NAV's
routing, place search, sights near a road and a flat map, and adding to another module) and 23
(drawing a film on a computer running MOTO-HUB Studio) ADV-SOLO 0.1.33,
contracts 24 (the weather after the rain) and 25 (one film drawn by every ready device at once)
ADV-SOLO 0.1.34, contract 26 (a project sent to a paired computer and edited there) ADV-SOLO
0.1.36; a module
that does not use them can keep declaring 10 and run on 0.1.25 and later. See [docs/contract.md](docs/contract.md#versioning).

## Community

Questions and module ideas: the **[MOTO-HUB Discord](https://discord.gg/FzhXZtPhC8)**, or an issue in this repository.

- **[motohub.techub.eu](https://motohub.techub.eu)**: the MOTO-HUB website, with news, release notes and the dashboard gallery.
- **[MOTO-HUB-modules](https://github.com/vincenzobpt/MOTO-HUB-modules)**: the catalogue the app reads, where published modules live.
- **[MOTO-HUB ADV-SOLO](https://github.com/vincenzobpt/MOTO-HUB-ADV-SOLO-releases)**: the app that loads your module.
