# Troubleshooting

MOTO-HUB logs everything about modules under the tag `MODULES`, and your own `host.log` lines
under your id in capitals. A diagnostics report sent from the app carries both.

## Installing

| Message | Cause |
|---|---|
| *That file is not a MOTO-HUB module package.* | Not a `.mhm`, or `module.jar` is missing from it. You may have picked the `.jar`. |
| *That module package carries no signature.* | `module.sig` is missing. |
| *That file does not describe a MOTO-HUB module.* | `META-INF/motohub-module.json` is missing or is not valid JSON. |
| *That module needs a newer version of MOTO-HUB.* | Your `contractVersion` is higher than the app's. Update the app, or build against an older `module-api`. |
| *That module is signed by its developer, not by us. Turn on developer mode to install it.* | As it says. |
| *That file is not signed by us and was refused.* | No `module.pub`, and not MOTO-HUB-signed. |
| *That module's signature does not match the key it carries…* | The jar was changed after signing, or the files come from two different builds. Run `modulePackage` again. |
| *… is installed as a MOTO-HUB module. Remove it before installing a developer build.* | A module we signed already has that `id`. |
| *… is installed from a different developer key. Remove it first.* | You signed with another key, on another machine or after losing the old one. |

## Loading

The card shows the module as installed, but nothing it offers appears. Look in the log for:

- `Module <id> needs module contract N and this app speaks M`: the app is too old.
- `Module <id> was built for module contract N, and this app needs at least M`: rebuild against a
  newer `module-api`.
- `Module <id> is a developer build and developer mode is off`: turn it back on.
- `Module <id> did not load: …` followed by an exception. Most often:
  - `ClassNotFoundException` for your entry class: `entryClass` does not match the class name.
  - `NoClassDefFoundError` for a library class: you called something the app does not carry at
    all. See [borrowed-libraries.md](borrowed-libraries.md).
  - `InstantiationException` / `NoSuchMethodException <init>`: the entry class needs a public
    no-argument constructor, and cannot be `object` or `abstract`.

A module that failed to load is not retried until its file changes. Reinstall it after fixing.

## Running

- **`NoSuchMethodError`** anywhere in your code: a borrowed method that R8 removed from the app.
  Run `checkModuleLinkage`; it would have caught it.
- **`AbstractMethodError`** calling into `host.ui` or another contract interface: a default
  argument, or a stale `module-api`. See [contract.md](contract.md#rules-that-are-easy-to-break).
- **Your page is blank or the app freezes when opening it**: `features()` or the composable is
  doing I/O or waiting on something. Load in the background and draw state.
- **Changes do not show up**: the old code is still loaded. Force-stop MOTO-HUB and open it again.

## Building

- *The Android SDK was not found*: set `ANDROID_HOME`, or create `local.properties` with
  `sdk.dir=/path/to/Android/sdk`.
- *No build-tools with d8*: install any recent build-tools with the SDK manager.
- A bare version number as the only error (`25.0.2`): your JDK is too new for this Android Gradle
  Plugin. Use JDK 17 or 21.
- `checkModuleLinkage` *needs the MOTO-HUB APK*: pass `-PmotohubApk=` with a downloaded
  ADV-SOLO release APK.
