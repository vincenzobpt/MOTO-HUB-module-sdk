# Packaging and signing

## The `.mhm` package

A module is distributed as **one file**, `<id>-<version>.mhm`: a zip with

| Entry | What |
|---|---|
| `module.jar` | The module: `classes.dex` and `META-INF/motohub-module.json` (plus any resources you pack). This is what gets loaded. |
| `module.sig` | An **Ed25519** signature over the whole of `module.jar`. |
| `module.pub` | *Developer builds only:* the public key that made the signature (X.509 DER). |

It is one file on purpose: two files that only mean anything together are two chances to pair
the wrong ones. The signature covers every byte that can be loaded.

`./gradlew :<module>:modulePackage` builds it into `build/module/out/`. `moduleJar` builds only
`module.jar`, for when someone else signs it.

## Who a signature can come from

MOTO-HUB checks the signature **when installing and again every time it loads the module**,
because nothing else does: Android vets the signer of an APK, but not a file an app loads itself.
A module runs with all of MOTO-HUB's permissions (location, Bluetooth, the motorcycle's
screen), so the app accepts exactly two kinds of signature.

### Signed by MOTO-HUB

The app carries one public key. A module signed with its private half is a **MOTO-HUB module**.
Only these can be installed from the in-app catalogue and updated from it. That key never leaves
the maintainer's machine; see [publishing.md](publishing.md) for how a third-party module gets
signed.

### Signed by you: developer mode

**MOTO-HUB → Modules → Developer mode** (ADV-SOLO 0.1.24 or later) lets **Install from a file…**
accept a package signed by the key it carries in `module.pub`. Such a signature proves only that
the file has not changed since its author signed it. It says nothing about who the author is,
which is why developer mode is off by default and asks for confirmation.

Rules the app enforces:

- Developer modules install **only from a file**. The catalogue and its updates stay MOTO-HUB-signed.
- The key is **pinned at first install**. A later file with the same `id` must be signed by the
  same key, or the rider has to remove the module first.
- A developer build **never replaces a MOTO-HUB module** with the same `id`. Remove the official
  one first, which is how you run your own build of `android-auto`.
- A MOTO-HUB-signed file **does** replace a developer build: the catalogue always wins.
- **Turning developer mode off stops every developer module from loading**, without removing it.
  Its card reads *DEVELOPER · NOT RUNNING*.
- The card shows the key's **fingerprint** (the first 16 hex digits of the SHA-256 of
  `module.pub`), the same value the plugin prints, so you can tell your build from anyone else's.

## Your developer key

Created by the plugin on first use:

```
~/.motohub/module-signing/developer.key   private half, PKCS#8 DER, owner-only
~/.motohub/module-signing/developer.pub   public half, X.509 DER
```

`-Pmotohub.moduleKeyDir=<dir>` (or the same line in `~/.gradle/gradle.properties`) puts it
somewhere else, for example on a CI machine.

- **Back it up.** Lose it and every phone with your module installed will refuse your updates
  until the module is removed and reinstalled. Its storage goes with it.
- **Never commit it.** The SDK's `.gitignore` excludes `*.key`, but the key does not belong in any
  repository.
- One key for all your modules is fine, and it is the simplest.

## Signing by hand

The format is simple enough to reproduce with OpenSSL, for example in a release pipeline:

```bash
openssl genpkey -algorithm ed25519 -out developer.pem                  # once
openssl pkey -in developer.pem -pubout -outform DER -out module.pub
openssl pkeyutl -sign -inkey developer.pem -rawin -in module.jar -out module.sig
zip -X my-module-1.0.0.mhm module.jar module.sig module.pub
```

And to check a package:

```bash
unzip -o my-module-1.0.0.mhm
openssl pkey -pubin -inform DER -in module.pub -out pub.pem
openssl pkeyutl -verify -pubin -inkey pub.pem -rawin -in module.jar -sigfile module.sig
```
