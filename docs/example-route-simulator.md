# Example: the route simulator

[`modules/flyby-route-simulator/`](../modules/flyby-route-simulator) is the complete, open-source
(AGPL-3.0) source of **Flyby Route Simulator**: a module that plans a route and rides it
virtually, and hands the app a trip it plays like any other. It is the reference for everything
contract 22 added, the way the Dashcam module is for a panel projection and Android Auto for a
whole-screen one.

[`examples/hello-module`](../examples/hello-module) shows the shape of a module. This one shows a
module that **makes things**: it asks the app for a route, turns it into a ride, saves the ride
where the rider finds it and opens it in another module.

## Where to look

| You want to… | Read | In the docs |
|---|---|---|
| Save a ride or a route the module made | `core/RideGenerator.kt`: `save`, `savePlanAsRoute` | [`ModuleRideWriter`](rides-and-scene.md#writing-rides-and-routes-moduleridewriter) |
| Ask for a route, its speed limits and its elevations | `core/RideGenerator.kt`: `prepare` | [`ModuleRouting`](rides-and-scene.md#routing-speed-limits-and-elevation-modulerouting) |
| Name a place | `core/RideGenerator.kt`: `prepare` (`reverse`) | [`ModulePlaces`](rides-and-scene.md#place-search-moduleplaces) |
| Open another module's feature on a ride | `core/RideGenerator.kt`: `openInFlyby` | [`ModuleBridge`](capabilities.md#opening-another-modules-feature-on-a-ride-openfeatureon) |
| Test a module without a host | `core/Services.kt`, `src/test/.../core/Fakes.kt` | below |
| Declare version and contract once | `build.gradle.kts`, `plugin/RouteSimulatorEntry.kt` | [contract.md](contract.md#the-manifest) |

The module's pages are where it draws a flat map to choose stops on
([`ModuleMapHost`](rides-and-scene.md#a-flat-map-modulemaphost)), searches places by name
([`ModulePlaces`](rides-and-scene.md#place-search-moduleplaces)), and adds an entry to the Flyby
module ([`ModuleExtensions`](capabilities.md#adding-to-another-module-moduleextensions-and-modulebridge)).

## How it is put together

Three packages, and the dependency only runs one way:

- **`sim`**: the simulation engine. Plain Kotlin with no Android and no contract in it: a route,
  a driving style and a seed in, ten samples a second out (position, speed, lean, engine speed,
  GPS accuracy). Parts of it are derived from
  [MockLocation](https://github.com/vincenzobpt/gps-mock-location), under the MIT licence;
  [`NOTICE.md`](../modules/flyby-route-simulator/NOTICE.md) carries the notice.
- **`core`**: everything that talks to the host. `RideGenerator.prepare` is the network phase
  (route, speed limits, elevations, the names of both ends), `generate` is pure and fast, and
  `save` hands the result to `host.rideWriter`. The three can be run apart, so a rider can
  regenerate a ride from the route already fetched, with a new seed, without asking the network
  again.
- **`plugin`**: the entry class and the capabilities, which is all the app ever sees.

Things worth copying:

- **Every host call can fail, and the module expects it.** `prepare` falls back on its own
  cruising speed when `speedLimits` returns `null`, leaves an elevation as unknown when
  `elevations` does, and turns a `ModuleRouteResult` that is not `ok` into the sentence the app
  wrote for the rider. A ride that cannot be saved comes back as a message, not an exception.
- **The blocking calls are marked as such.** Every method of `RideGenerator` that touches the host
  blocks, and its header says so: whoever calls it does so from a worker, never from the UI.
- **The host is cut to the slice the module uses.** `Services` holds only the five interfaces the
  generator touches (`ModuleRouting`, `ModulePlaces`, `ModuleRideWriter`, `ModuleBridge`,
  `ModuleRideLibrary`) and is built from the host in one line. The tests build it from fakes, so
  the whole generator is tested on a laptop without a phone or an app.
- **Version and contract in one place.** The build compares `plugin/RouteSimulatorEntry.kt` with
  the values in `build.gradle.kts` and fails if they drift, because two sources of truth that
  nobody compares go stale.
- **Ride length is capped before the network is asked.** A route cannot be shorter than the
  straight lines between its stops, so a rider who asks for an impossible distance hears so at
  once, not after the server has said no.

## Build it

Like the others in this repository: `./gradlew :modules:flyby-route-simulator:pushModule`
(see [getting-started.md](getting-started.md)). It declares the contract it uses, so it installs
on ADV-SOLO 0.1.33 or later.
