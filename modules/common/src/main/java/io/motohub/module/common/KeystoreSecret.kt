// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.module.common

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals a secret (an API key) with an AES key kept in the Android Keystore under [alias], so the
 * file in the module's storage is not the secret. One alias per module, and it never changes:
 * what a module sealed under it on a rider's phone must still open after an update.
 */
open class KeystoreSecret(private val alias: String) {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val existing = store.getKey(alias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray())
        val out = ByteArray(iv.size + sealed.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(sealed, 0, out, iv.size, sealed.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** The secret, or null when the text holds nothing this Keystore can open (restored to another phone). */
    fun open(text: String): String? = try {
        val raw = Base64.decode(text, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_BYTES))
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val IV_BYTES = 12
    }
}
