// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.plugin

import io.motohub.android.aaplugin.AaIdentityProvider
import java.io.InputStream

/**
 * The head-unit identity, carried by this module.
 *
 * It identifies THIS receiver to Android Auto and nothing else in MOTO-HUB has any use for it, so
 * it travels with the code that presents it rather than with the app that hosts it. A module is a
 * dex container and has no Android resources, but its zip entries are ordinary Java resources -
 * which is how these are read.
 *
 * Absent in a module built without `-PincludeAndroidAutoIdentity=true`: [isAvailable] then answers
 * false and the receiver refuses to start, saying so, rather than failing later inside a TLS
 * handshake with nothing to present.
 */
internal object ModuleIdentity : AaIdentityProvider {

    private const val CERTIFICATE = "motohub/aa_cert"
    private const val IDENTITY = "motohub/aa_identity_data"

    override fun isAvailable(): Boolean = open(CERTIFICATE) != null && open(IDENTITY) != null

    override fun openCertificate(): InputStream =
        open(CERTIFICATE) ?: error("This Android Auto module was built without its identity.")

    override fun openIdentityData(): InputStream =
        open(IDENTITY) ?: error("This Android Auto module was built without its identity.")

    private fun open(name: String): InputStream? =
        ModuleIdentity::class.java.classLoader?.getResourceAsStream(name)
}
