# MOTO-HUB module SDK

Everything you need to write a **module** for MOTO-HUB (ADV-SOLO), the Android app that connects
a phone to a motorcycle's TFT dashboard.

A module is a single file (`.mhm`) that MOTO-HUB downloads or installs from storage and **loads
into its own process while it runs**. It can add pages to the app, fill the motorcycle's screen
(Android Auto is a module), put the Ride Dashboard on a third-party head unit, receive
turn-by-turn guidance and projected audio, and more. The app itself knows nothing about any
particular module: it asks for *capabilities* by interface and draws whatever the module offers.

## What is in this repository

| Path | What | Licence |
|---|---|---|
| [`module-api/`](module-api) | The contract every module is compiled against. The app ships these exact classes. | Apache-2.0 |
| [`build-logic/`](build-logic) | The `motohub.module` Gradle plugin: manifest, dex, signing, `.mhm`, linkage check. | Apache-2.0 |
| [`examples/hello-module/`](examples/hello-module) | The smallest useful module: one page, one button, a file in its own storage. Start here. | Apache-2.0 |
| [`modules/android-auto/`](modules/android-auto) | The complete source of the Android Auto module MOTO-HUB installs. | **AGPL-3.0** |
| [`docs/`](docs) | The developer guide. | Apache-2.0 |

The contract and tooling are Apache-2.0 so that you can write a module under any licence you
like, including a closed one. The Android Auto module is AGPL-3.0 because it is derived from
[headunit-revived](https://github.com/andreknieriem/headunit-revived); its source is published
here to meet that licence, and each published version of it has a matching tag in this repository.

## Quick start

Requirements: JDK 17 or 21, the Android SDK (platform 36, build-tools with `d8`), `adb`, and a
phone running **ADV-SOLO 0.1.24 or later**.

```bash
git clone https://github.com/vincenzobpt/MOTO-HUB-module-sdk
cd MOTO-HUB-module-sdk
./gradlew :examples:hello-module:pushModule
```

Then on the phone: **MOTO-HUB → Modules → Developer mode** (on), **Install from a file…**, pick
`hello-0.1.0.mhm` from Download. The module's card appears; **About** opens the page it brought.

The full walk-through, including how to start your own module, is in
[docs/getting-started.md](docs/getting-started.md).

## The developer guide

1. [Getting started](docs/getting-started.md): build, install and change the example
2. [The contract](docs/contract.md): how a module is loaded, the manifest, versioning and the rules
3. [Capabilities](docs/capabilities.md): everything a module can offer, and what the host lends it
4. [Borrowed libraries](docs/borrowed-libraries.md): what you may call, and the linkage check (**read this one**)
5. [Packaging and signing](docs/packaging-and-signing.md): the `.mhm` format, your developer key, developer mode
6. [Publishing](docs/publishing.md): getting a module into the in-app catalogue
7. [Troubleshooting](docs/troubleshooting.md)

## Status

The contract is at **version 10**. It only grows by appending, and the app still loads modules
built against contract 3 or later. See [docs/contract.md](docs/contract.md#versioning).

Questions and module ideas: the MOTO-HUB Discord, or an issue in this repository.
