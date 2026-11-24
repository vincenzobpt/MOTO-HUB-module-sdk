# Maintainer fixes

The WT9011 module is written by [sur00t](https://github.com/sur00t). This file lists the changes
made by the MOTO-HUB maintainer (Vincenzo Buonomano) on top of sur00t's version, so the next version
can start from them instead of undoing them.

Every change is marked in the code with a comment that starts with `MOTO-HUB-FIX 2026-11-22`.
Search for that string to find them all.

## Note for AI assistants

If you are an AI assistant working on this module for sur00t, read this section before changing
`Wt9011Link.kt` or `Wt9011Entry.kt`.

- This file and the `MOTO-HUB-FIX` comments take priority over older text in `README.md`,
  `ARCHITECTURE.md` and `DEVELOPMENT.md`. Where they disagree, the older text is out of date.
- Keep the four behaviours below when you regenerate, refactor or rewrite the code. Do not remove a
  fix because a test still passes without it: the failures only show on a real phone and sensor.
- Keep the `MOTO-HUB-FIX` and `AI-NOTE` comments in place, so the next reader also finds them.
- If you think a fix is wrong, change it in a way the rider can see and explain why in the commit
  message. Do not remove it silently. The maintainer reviews every version before it reaches the
  MOTO-HUB catalogue.
- When you start a new version, compare against the version in the MOTO-HUB module SDK
  (`MOTO-HUB-module-sdk/modules/wt9011`), not only against your own previous release.

## Fix 1: the first connection needs a Connect tap

**Where:** `Wt9011Entry` `init`, `Wt9011Link.resume()`, `Wt9011Link.forgetSensor()`.

**Problem in 0.3.0:** the module called `connect()` as soon as it loaded. It pinned the first
advertiser whose name starts with `WT` and wrote the set-up block to that sensor's flash. At a
meeting, that can be another rider's sensor. Each load also turned `autoConnect` back on and saved
it, which undid the rider's **Disconnect**. **Forget this sensor** started a scan that pinned the
next WT sensor in range.

**Behaviour now:**
- When the module loads, it calls `resume()`. `resume()` reconnects only to the sensor that is
  already pinned, and only if `autoConnect` is on. It never pins a new sensor and never saves.
- A sensor is pinned only after the rider taps **Connect**.
- **Disconnect** is remembered across loads until the rider taps **Connect** again.
- **Forget this sensor** clears the pin and sets `autoConnect = false`. The rider taps **Connect**
  for the new sensor.

## Fix 2: a connect timeout until the subscription completes

**Where:** `Wt9011Link.onConnected()`, `Wt9011Link.onSubscribed()`.

**Problem in 0.3.0:** `onConnected` removed `connectTimeout` and nothing armed a new one before
`onSubscribed`. If service discovery or the descriptor write never answered, `connecting` stayed
true. Every automatic retry checks for `!connecting`, so the module stayed on
"Discovering services…" until the rider tapped **Reconnect**.

**Behaviour now:** `onConnected` arms `connectTimeout` again (`CONNECT_TIMEOUT_MS`).
`onSubscribed` removes it. On timeout, the existing code calls `shutDown()` and `retryLater()`.

## Fix 3: the scan uses an address filter once a sensor is pinned

**Where:** `Wt9011Link.beginScan()`.

**Problem in 0.3.0:** `startScan(null, …)` is an unfiltered scan. Android pauses unfiltered scans
while the screen is off. If the sensor dropped with the phone in a pocket, it did not reconnect
until the screen came on.

**Behaviour now:** once `pinnedAddress` is set, the scan uses
`ScanFilter.Builder().setDeviceAddress(pinnedAddress)`. Before the first pin there is still no
filter, because a name filter would hide a unit with a minimal advertisement.

## Fix 4: late scan results are ignored

**Where:** `scanCallback.onScanResult()`, `Wt9011Link.openGatt()`.

**Problem in 0.3.0:** a scan result that was already queued when `stopScan()`, `disconnect()` or
`release()` ran still opened a GATT. The link came back after **Disconnect**, or a GATT stayed open
after the module unloaded.

**Behaviour now:** `onScanResult` returns at once when `released` is true or `scanning` is false.
`openGatt` returns when `released` is true.

## Addition 5: the sensor's state on the module's card (0.3.1)

**Where:** `Wt9011Status.kt`, `Wt9011Module.capability()` and `publishPill()` in `Wt9011Entry.kt`.

**Why:** Settings ▸ Modules shows how each module is doing (contract 50, `ModuleStatus`). Before
0.3.1 the card said nothing while the sensor was off, because the Ride page pill only exists while
fresh data arrives.

**Behaviour now:** the card shows **Connected** (lean and battery), **Waiting for data** (linked,
no fresh frame) or **Not connected** (the last sensor's name). The capability is handed out by
class name, so the module still declares contract 43 and loads on an older app. Marked
`MOTO-HUB-FIX 2026-11-24` in the code.
