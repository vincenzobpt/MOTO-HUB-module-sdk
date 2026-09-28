# Borrowed libraries

**Read this before you ship anything.** It describes the one failure that loading a module
in-process makes possible, and it is silent until a rider hits it.

## What a module borrows

Your module is dexed on its own, and every library it compiles against is `compileOnly`. At
runtime its class loader's parent is MOTO-HUB's, so these come from **the app's APK**:

| Library | Version (see `gradle/libs.versions.toml`) | How much the app keeps |
|---|---|---|
| Kotlin stdlib | as the app | `kotlin.jvm.functions.*`, `kotlin.jvm.internal.*`, `kotlin.Metadata` whole; the rest **by name only** |
| kotlinx.coroutines | as the app | **by name only** |
| Jetpack Compose | BOM as the app | **by name only**, except `androidx.compose.runtime.ComposerKt`, kept whole |
| `androidx.car.app` (Car App Library) | as the app | whole |
| `androidx.lifecycle` | as the app | `DefaultLifecycleObserver` and `LifecycleOwner` whole; the rest by name only |
| `androidx.core.graphics.drawable.IconCompat` | as the app | whole |
| protobuf-java, Conscrypt | as the app | whatever the app itself uses |
| Bouncy Castle (`org.bouncycastle`) | not exported | only the Ed25519 signer the app's signature check uses. Do not borrow it; if you need it, bundle your own **relocated** copy (see below) |
| `io.motohub.android.module` (the contract) | this repository | whole |
| Android framework (`android.*`, `java.*`, `org.json`, …) | the phone's | everything; not the app's to shrink |

`gradle/libs.versions.toml` in this repository is exported from the app's own build, so the
versions you compile against are the ones the app carries.

## The trap

MOTO-HUB's release APK is shrunk by R8, and R8 decides what to keep by looking at what **the
app** calls. It cannot see your module. "Kept by name only" means a class keeps its name, but
**any method the app itself never calls may have been removed from it**.

Your module then loads perfectly. The class is there, and the Compose screen draws. The missing
method throws `NoSuchMethodError` **the first time your code reaches it**, which may be months
later, on an error path, on a background thread, mid-ride. This happened to the Android Auto
module: a `lastOrNull()` inside its error logger was missing from every build for months, and it
killed the app for seven riders the first time their receiver had an error to log.

A missing *class* fails loudly at load time. A missing *method* is the silent case, and it is the
one to worry about.

## The linkage check

The plugin reads every method your module's dex references and resolves each one against a real
MOTO-HUB APK, through superclasses and interfaces, the way the runtime does:

```bash
./gradlew :modules:<your-module>:checkModuleLinkage -PmotohubApk=/path/to/ADV-SOLO-x.y.z.apk
```

```
hello-0.1.0.jar: 41 borrowed method reference(s), 0 unresolved
```

It fails the build on anything unresolved and says why:

```
Lkotlin/collections/ArraysKt;lastOrNull([Ljava/lang/Object;)Ljava/lang/Object;  — the class is in the APK but this method was shrunk out of it
```

**Run it against every APK version you intend to support**, at least the current release. The
report is written to `build/reports/module-linkage.txt`.

### When it fails

In order of preference:

1. **Stop calling it.** Write the few lines yourself (a loop instead of `lastOrNull()` or
   `toHashSet()`), or call
   the framework's equivalent (`java.util.*`, `android.*`), which is never shrunk. This is always
   the right fix on error and logging paths.
2. **Ask for a keep rule.** If the method is genuinely needed (a Compose API, a coroutine
   builder), open an issue naming it. A keep rule ships with the next app release, and your module
   must then declare the contract version of that release so that older apps refuse it cleanly.
3. **Allow-list it**, only if you can prove the reference is unreachable: one descriptor per line
   in `module-linkage-allowed.txt` next to your `build.gradle.kts`, with a `#` comment saying why.
   A wrong entry is a crash on a motorcycle.

## Bringing your own library

You can bundle a library the app does **not** carry: change `compileOnly` to `implementation`,
and the plugin dexes it into your module. Be careful:

- Never bundle anything from the table above, not even a different version. The parent class
  loader is asked first, so your copy is ignored for any class the app has, and mixing two
  versions of one library fails in ways that are hard to read.
- Relocate (shade) a bundled library into your own package if there is any chance the app will
  start carrying it later.
- Everything you bundle counts against your module's size, and it is downloaded on a phone,
  possibly over the motorcycle's network.

## Why it works this way

Borrowing is what keeps modules small and lets `@Composable` functions and `StateFlow`s cross the
boundary as ordinary calls. Obfuscating these libraries' names protected nothing, because they are
open source, so the app keeps their names. Keeping every method of every library would make the
APK carry all of Compose. The linkage check is what makes the trade safe.
