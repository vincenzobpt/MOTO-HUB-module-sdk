// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.dawarich

import io.motohub.module.common.KeystoreSecret

/** Seals the API key with an Android Keystore key, so the file in the module's storage is not it. */
object Secret : KeystoreSecret("dawarich-module-key")
