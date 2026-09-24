# Android Auto module

The complete source of the `android-auto` module that MOTO-HUB installs from its module
catalogue. It turns the phone into an Android Auto head unit on the loopback and hands the
picture to MOTO-HUB, which composes and streams it to the motorcycle's dashboard.

**Licence: AGPL-3.0-only.** It is derived from headunit-revived; see [NOTICE](NOTICE). Every
published version of the module has a tag in this repository, `android-auto-<version>`, pointing
at the source it was built from.

## Building it

```bash
./gradlew :modules:android-auto:modulePackage
```

This builds a developer-signed `android-auto-<version>.mhm`. MOTO-HUB installs it with developer
mode on, in place of the published one if you remove that first. Its version and contract come
from `module.properties`, which records the published release this source matches.

### The head-unit identity

Android Auto only talks to a head unit that presents a certificate it accepts. The published
module carries one; **this source does not**. A module built from here loads and starts, and then
reports that it has no identity to present. If you have your own, put the two files `aa_cert` and
`aa_identity_data` in `modules/android-auto/identity/` (ignored by git) and they are packed into
the module where `ModuleIdentity` reads them.

## Where to look

| Package | What |
|---|---|
| `aa.plugin` | The module's entry point, the capabilities it answers, and its pages |
| `aa` | The receiver: self-mode wake-up, the AAP transport and handshake, video, audio, input, guidance |
| `aa.proto` | Generated protocol-buffer messages of the Android Auto protocol |
| `aaplugin` | Constants shared with the host (ports, entry class) |

It is also the most complete example of [capabilities](../../docs/capabilities.md) in practice:
`ModuleProjection`, `ModuleNavigation`, `ModuleAudio`, `ModuleAccessoryProbe`,
`ModuleAccessoryBridge` and `ModuleFeatures`.
