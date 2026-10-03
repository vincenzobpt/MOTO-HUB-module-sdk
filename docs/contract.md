# The contract

## How a module is loaded

A module file is a zip holding `classes.dex` (one or more) and `META-INF/motohub-module.json`.
MOTO-HUB keeps installed modules in its private storage and, the first time something asks for
one, does this:

1. **Reads the manifest out of the zip**, before running any of it: the id must match the
   directory it is filed under, and the contract version must be one this app speaks.
2. **Checks the signature**, every time, not only at install time: a file that passed on the way
   in could have been replaced afterwards.
3. Builds a `DexClassLoader` over the file whose **parent is the app's own class loader**, loads
   the class named by `entryClass`, calls its no-argument constructor, and calls
   `create(host)`.
4. Keeps the resulting `MotoHubModule` for the life of the process.

The parent class loader is the heart of the design. Everything in `io.motohub.android.module`,
and every library the app carries (Kotlin, coroutines, Compose, protobuf, Conscrypt, the Car App
Library), resolves to **the app's copy**. Your module compiles against them `compileOnly` and never
bundles them. That makes a module small (the example is 13 KB) and lets an interface cross the
boundary as an ordinary call. It also has a cost, explained in
[borrowed-libraries.md](borrowed-libraries.md).

A module runs **inside MOTO-HUB's process with MOTO-HUB's permissions**. It cannot declare
components: no activities, services or receivers of its own, and no manifest merge. Anything
that needs a component is done by the app on the module's behalf, through a capability. That is
why the foreground service a projection runs in belongs to the app.

A failure to load is remembered against that exact file (its size and modification time), so a
broken module is not retried on every frame. Installing a new file clears the memory.

## The manifest

Written for you by the `motohub.module` plugin from the `motohubModule { }` block:

```json
{
  "id": "hello",
  "version": "0.1.0",
  "contractVersion": 10,
  "entryClass": "com.example.hello.HelloModuleEntry",
  "displayName": "Hello module",
  "description": "The smallest useful MOTO-HUB module: one page, one button."
}
```

| Field | Rule |
|---|---|
| `id` | Letters, digits, `-`, `_`. Permanent. The directory the module is filed under, and what an update must match. |
| `version` | Free text. The app compares it for **equality**, not order: "a different version is published" is the only thing it ever says. |
| `contractVersion` | The `CONTRACT_VERSION` of the `module-api` you built against. |
| `entryClass` | Implements `MotoHubModuleEntry`. Public, with a public no-argument constructor. |
| `displayName`, `description` | What the rider reads on the module's card. |

Your `MotoHubModule.manifest` should report the same values. The app trusts the file, not the
object, but a module that disagrees with itself is confusing to debug.

## The entry point

```kotlin
class HelloModuleEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = HelloModule(host)
}

private class HelloModule(private val host: MotoHubModuleHost) : MotoHubModule {
    override val manifest = ModuleManifest(/* ... */)

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        else -> null
    }

    override fun release() { /* drop listeners, stop threads, close sockets */ }
}
```

`capability(type)` is **the whole of how the app reaches your module**. It asks for an interface
it ships itself and gets your implementation or `null`. `null` is an ordinary answer. The caller
always has a path for it, so answer only what you really implement.

`release()` is called before the module is unloaded, replaced or removed. Anything you registered
with the host (key sinks, guidance listeners, audio sinks) must be cleared here: the app has no
other way to know your objects are gone.

## What the host lends you

`MotoHubModuleHost` is the same object for the life of your module:

| Member | Use |
|---|---|
| `context` | The application `Context`. |
| `storage` | A directory of your own. Survives app updates, is deleted when the module is removed. Write nothing anywhere else. |
| `log` | Lines go to MOTO-HUB's event log, tagged with your id in capitals, and into diagnostics reports. |
| `ui` | The app's own components for your pages; see [capabilities.md](capabilities.md#pages-modulefeatures-and-moduleui). |
| `openFeature(id)` | Opens another of **your** features by id. Unknown ids do nothing. |
| `session` | The single session runtime shared with the app; see [capabilities.md](capabilities.md#projection-moduleprojection). |
| `keySinks` | Where a running projection registers to receive handlebar and phone keys. |
| `dashboardNetwork()` | The motorcycle's Wi-Fi SSID and password while connected, else `null`. |
| `dashboard`, `guidance` | The Ride Dashboard and route guidance, for navigator modules. |
| `rides` | The rider's recorded rides and saved routes, read-only (contract 11); see [rides-and-scene.md](rides-and-scene.md). |
| `scene` | A 3D terrain scene your module directs, and can export to a video (contract 11); see [rides-and-scene.md](rides-and-scene.md#the-3d-scene-modulescenehost). |
| `openedFor()` | The ride or route a `RIDE_ACTION` or `ROUTE_ACTION` feature was opened on, else `null` (contract 14). |
| `ai` | The language model the rider set up in the app, without its key (contract 14); see [rides-and-scene.md](rides-and-scene.md#the-riders-language-model-moduleai). |
| `rideWriter` | Saves simulated rides and routes into TRIPS and the NAV (contract 22); see [rides-and-scene.md](rides-and-scene.md#writing-rides-and-routes-moduleridewriter). |
| `routing` | The NAV's routing, speed limits and elevation (contract 22); see [rides-and-scene.md](rides-and-scene.md#routing-speed-limits-and-elevation-modulerouting). |
| `places` | The NAV's place search (contract 22); see [rides-and-scene.md](rides-and-scene.md#place-search-moduleplaces). |
| `maps` | A flat map the app draws and your module directs (contract 22); see [rides-and-scene.md](rides-and-scene.md#a-flat-map-modulemaphost). |
| `modules` | Whether another module is installed, opening its features on a ride, and what other modules add to yours (contract 22); see [capabilities.md](capabilities.md#adding-to-another-module-moduleextensions-and-modulebridge). |

## Rules that are easy to break

These all come from real failures on real motorcycles.

- **No default arguments on anything that crosses the boundary.** Kotlin compiles a default into
  a synthetic `$default` bridge, and your module and the app compile that bridge separately. The
  result is an `AbstractMethodError` at the first call. `ModuleUi` has none for this reason. Pass
  every argument, including `technical = false`.
- **State every `@Composable` applier explicitly** when you declare composable interfaces of your
  own that cross into the app. Declarations that reach a compiler as a compiled dependency have
  no source file to infer it from.
- **Never block the thread you are called on.** Audio and guidance callbacks arrive on transport
  threads; `features()` and `capability()` are asked from the UI.
- **Do not throw out of a capability call.** The app catches and logs, but the feature then looks
  broken to the rider. Return `null`, `false`, or a message.
- **Your code runs mid-ride.** A crash in a module is a crash of MOTO-HUB, on a motorcycle, with
  the phone in a pocket. Error paths are where the missing-method trap bites; see
  [borrowed-libraries.md](borrowed-libraries.md).

## Versioning

`MotoHubModuleContract.CONTRACT_VERSION` (currently **22**) is bumped whenever anything in
`io.motohub.android.module` changes shape. The contract **only grows by appending**: new
capabilities, and new members on interfaces the *app* implements. An older module simply does
not answer a capability it predates, and never calls a host member it does not know.

The app refuses a module whose `contractVersion` is:

- **higher** than the app's own: the module needs a newer app ("Update the app first");
- **lower** than `MINIMUM_CONTRACT_VERSION` (currently **3**): the shapes changed in a way that
  is not backwards compatible.

So **compile against the newest `module-api`, and declare the highest contract whose members you
actually use**. A module that declares 10 installs on every app that speaks 10 or more, even when
it was compiled against 22. Declaring more than you use only turns away riders on older apps;
declaring less lets an older app load your module and fail with `AbstractMethodError` at the
first call it does not have. The example declares 10 for that reason, and so does the Android
Auto module. Every member added after contract 10 names its contract in its KDoc, and the full
history is in the KDoc of `MotoHubModuleContract`.

| Contract | Adds | ADV-SOLO that installs your build |
|---|---|---|
| 10 | `ModuleAccessoryBridge` | 0.1.25, the first with developer mode |
| 11 | `host.rides`, `host.scene` | 0.1.29 |
| 12 | `ModuleScene.setLook`, `setMarkers`; `ModuleRideLibrary.engineRpm`; `ModuleUi.Backdrop` | 0.1.29 |
| 13 | `ModuleScene.export` | 0.1.29 |
| 14 | `RIDE_ACTION`, `ROUTE_ACTION`, `TRIPS_HEADER`; `host.openedFor()`, `host.ai`; scene lens, engine, weather, extras, blend | 0.1.29 |
| 15 | `ModuleExportSpec.draft`, `realTime`, `captureSpeed`, `warmUp`, `hevc` | 0.1.30 |
| 16 | `ModuleScene.setCredits` | 0.1.31 |
| 17 | `ModuleScene.setActionPath`, `ModuleSceneAction`; real motorcycles among the `ModuleSceneRider` figures | 0.1.33 |
| 18 | `ModuleSceneEngine.ULTRA` | 0.1.33 |
| 19 | `ModuleRenderSettings`; `ModuleExportSpec.render`, `segments`, `preview`; `ModuleScene.setDiagnostics`, `setBasicMode` | 0.1.33 |
| 20 | `ModuleProjectionReach`, `ModuleProjectionTile` | 0.1.33 |
| 21 | `ModuleRenderSettings.realistic` (the Real 3D engine's ULTRA-REALISTIC tier) | 0.1.33 |
| 22 | `host.rideWriter`, `host.routing`, `host.places`, `host.maps`, `host.modules`; `ModuleExtensions`; `ModuleRideLibrary.isSimulated` | 0.1.33 |

This repository tags every contract release as `contract-<n>`.
