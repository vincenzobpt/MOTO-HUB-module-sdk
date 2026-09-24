// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see LICENSE.
package motohub

import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Your Ed25519 developer key, made on first use and kept outside every project.
 *
 * It lives in `~/.motohub/module-signing/` (or `-Pmotohub.moduleKeyDir=`) rather than in the
 * repository, so it is not committed by accident and every module you write is signed by the same
 * key. That matters on the phone: MOTO-HUB pins the key a developer module was first installed
 * with, and only accepts an update of that module signed by the same key. Lose the key and the
 * phone will ask you to remove the module before installing it again - nothing worse.
 *
 * `developer.key` is the private half (PKCS#8 DER) and `developer.pub` the public half (X.509 DER),
 * which is what goes into every package as `module.pub`.
 */
internal object DeveloperKey {

    fun directory(override: String?): File =
        override?.let(::File) ?: File(System.getProperty("user.home"), ".motohub/module-signing")

    fun loadOrCreate(directory: File, logger: Logger): KeyPair {
        val privateFile = File(directory, "developer.key")
        val publicFile = File(directory, "developer.pub")
        val factory = KeyFactory.getInstance("Ed25519")
        if (privateFile.isFile && publicFile.isFile) {
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
            )
        }
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        directory.mkdirs()
        privateFile.writeBytes(pair.private.encoded)
        publicFile.writeBytes(pair.public.encoded)
        runCatching {
            Files.setPosixFilePermissions(
                privateFile.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
        logger.lifecycle(
            "Created a developer key in $directory (fingerprint ${fingerprint(pair.public.encoded)}). " +
                "Back it up: updates of a module must be signed by the key it was installed with."
        )
        return pair
    }

    /** The same fingerprint MOTO-HUB shows next to a developer module. */
    fun fingerprint(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey)
            .take(8).joinToString("") { "%02x".format(it) }
}
