# Publishing a module

A module can reach riders in two ways.

## 1. Share the file

Publish your developer-signed `.mhm` wherever you like: your own GitHub releases, a website, a
Discord message. Riders turn on developer mode and install it from a file. This needs nobody's
permission, and it is the right way to test with a few riders. They see *DEVELOPER* and your key's
fingerprint on the card, and they should know what that means.

## 2. The in-app catalogue

**Modules** lists what the catalogue offers, and installs and updates it with one tap. Catalogue
modules must be **signed by MOTO-HUB**, because a rider installing from there is trusting
MOTO-HUB, not you.

The catalogue is `index.json` in
[vincenzobpt/MOTO-HUB-modules](https://github.com/vincenzobpt/MOTO-HUB-modules):

```json
[
  {
    "id": "android-auto",
    "displayName": "Android Auto",
    "summary": "Runs Android Auto on the motorcycle's screen.",
    "owner": "vincenzobpt",
    "repository": "MOTO-HUB-modules"
  }
]
```

`owner`/`repository` is a **public** GitHub repository whose releases carry the module. The app
reads its releases anonymously and takes the newest release with an asset named exactly
`<id>-<version>.mhm`, where the version starts with a digit. That rule is what stops
`android-auto` from matching `android-auto-extras-1.0.mhm`. The repository can be yours: only the
signature has to be MOTO-HUB's.

### How to get there

1. **Open an issue** in this repository describing the module: what it does, which capabilities
   it offers, what it needs from the phone.
2. **Share the source** with the maintainer for review, publicly or privately. Nothing is signed
   unseen, because a signed module runs with every permission MOTO-HUB has.
3. The module passes `checkModuleLinkage` against the current release.
4. The maintainer builds your `module.jar` from the reviewed source (`./gradlew :<module>:moduleJar`)
   and returns it signed as `<id>-<version>.mhm`.
5. You attach that file to a release of your repository, and your entry is added to `index.json`.

Every new version goes through steps 2 to 5 again. Updates reach riders through the card's
**Update** button.

## Licence

`module-api`, the plugin and the example are **Apache-2.0**: your module may use any licence,
including a closed one. If you start from `modules/android-auto`, your module is a derivative of
AGPL-3.0 code and must be released under the AGPL-3.0, with its source available to everyone you
distribute it to.

## Checklist before you publish

- [ ] `id` is final. Changing it later makes it a different module.
- [ ] `contractVersion` is the one you built against, and the module was tested on the oldest app
      you claim to support.
- [ ] `checkModuleLinkage` is clean against the current ADV-SOLO release.
- [ ] `release()` clears everything you registered with the host.
- [ ] A `MODULES` page says what the module does and whether it can run right now.
- [ ] Nothing is written outside `host.storage`.
- [ ] Error messages are in words a rider can act on.
