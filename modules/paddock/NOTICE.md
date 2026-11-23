# Paddock — credits and third-party notices

Paddock is free software under the Mozilla Public License 2.0 (see `LICENSE`). The MPL applies
file by file, so Paddock can carry the libraries below under their own licences. It stands on the
work of others:

- **Columba** by torlando-tech — <https://github.com/torlando-tech/columba>, Mozilla Public
  License 2.0. Paddock's position format (Sideband Telemeter in LXMF field 0x02), the `lxma://`
  identity link and much of what we learned about running Reticulum on Android come from
  Columba. Files derived from Columba say so in their header.
- **Reticulum (RNS)** and **LXMF** by Mark Qvist — <https://reticulum.network>, bundled as Python
  packages under the **Reticulum License**: MIT-style terms (keep the copyright and permission
  notice) plus two conditions every user of Paddock inherits:
  - the software shall not be used in any kind of system which includes amongst its functions
    the ability to purposefully do harm to human beings;
  - the software shall not be used, directly or indirectly, in the creation of an artificial
    intelligence, machine learning or language model training dataset, including any use that
    contributes to the training or development of such a model or algorithm.

  Copyright (c) 2016-2026 Mark Qvist. The full text ships with the packages and is at
  <https://github.com/markqvist/Reticulum/blob/master/LICENSE>.
- **Sideband** by Mark Qvist — the Telemeter format Paddock speaks for positions.
- **Chaquopy** by Chaquo Ltd — <https://chaquo.com/chaquopy/>, MIT License. The CPython runtime
  Paddock starts inside MOTO-HUB.
- **CPython** — Python Software Foundation License.
- **cryptography**, **cffi** (PyCA) — Apache-2.0 / BSD; **pyserial** — BSD; **u-msgpack**,
  if present — MIT.
- **ZXing** — <https://github.com/zxing/zxing>, Apache License 2.0. QR codes.
