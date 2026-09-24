# Getting started

## 1. Set up

- **JDK 17 or 21.** Newer JDKs are not supported by this Android Gradle Plugin.
- **Android SDK** with platform `android-36` and a build-tools that has `d8`. The plugin finds
  the SDK through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or the `sdk.dir` Android Studio writes to
  `local.properties`.
- **A phone with ADV-SOLO 0.1.24 or later**, reachable over `adb`.
- Opening the repository in Android Studio works; nothing here needs it.

## 2. Build and install the example

```bash
./gradlew :examples:hello-module:modulePackage
```

This compiles the module, dexes it, writes its manifest, and signs it with **your developer
key**. The first run creates that key in `~/.motohub/module-signing/` and prints its fingerprint.
Back the key up; see [packaging-and-signing.md](packaging-and-signing.md#your-developer-key).
The result is `examples/hello-module/build/module/out/hello-0.1.0.mhm`.

`pushModule` does the same and copies the file to the phone's `Download` folder:

```bash
./gradlew :examples:hello-module:pushModule
```

On the phone:

1. **MOTO-HUB → Modules**, scroll to the bottom, turn on **Developer mode** and confirm.
2. **Install from a file…** → `Download/hello-0.1.0.mhm`.
3. The card reads **DEVELOPER**, with your key's fingerprint under the version.
4. **About** opens the module's page. Press the button, leave, come back: the count survives,
   because it is written to the module's own storage.

The module's log lines (`Hello module loaded.`, `Hello button pressed (n).`) go to MOTO-HUB's
event log under the tag `HELLO`, so they also appear in a diagnostics report sent from the app.

## 3. Check it against the app it will run in

```bash
./gradlew :examples:hello-module:checkModuleLinkage -PmotohubApk=/path/to/ADV-SOLO-x.y.z.apk
```

Download the APK from the ADV-SOLO releases page, the same version your riders run. **Do not skip
this step.** A module borrows Kotlin, coroutines and Compose from the app, and the app is shrunk
by R8. A method your module calls may simply not be there, and nothing tells you until a rider
reaches that line. [borrowed-libraries.md](borrowed-libraries.md) explains why, and what to do
when the check fails.

## 4. Start your own module

1. Copy `examples/hello-module` to `modules/<your-module>`.
2. Add it to `settings.gradle.kts`: `include(":modules:<your-module>")`.
3. Rename the package (`com.example.hello` → yours) and update `android.namespace`.
4. In `build.gradle.kts`, set `motohubModule { id, version, entryClass, displayName, description }`.
   - `id` is permanent: it is what the app files your module under, and what an update must
     match. Letters, digits, `-` and `_`.
   - `contractVersion` is `MotoHubModuleContract.CONTRACT_VERSION` from the `module-api` you
     compile against. Leave it as the example has it, unless you are deliberately targeting an
     older app; see [contract.md](contract.md#versioning).
5. Keep the `ModuleManifest` your module reports in step with those values (the example shows
   where).
6. Every dependency stays `compileOnly` unless you are certain the app does not already carry it.
   See [borrowed-libraries.md](borrowed-libraries.md#bringing-your-own-library).

Then decide what your module *offers*: [capabilities.md](capabilities.md).

## Iterating

The loop is: edit → `pushModule` → **Install from a file…** again. Installing a new build of the
same id replaces the old one, as long as it is signed by the same developer key.

Installing drops the old copy, and MOTO-HUB loads the new file the next time it asks the module
for anything. A session that is already running (a projection on the bike, for example) keeps the
old code until it ends. When in doubt, force-stop the app before testing.
