// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Ported from headunit-revived (AGPLv3): ssl/SingleKeyKeyManager.kt
// Presents the head-unit certificate + private key (res/raw/aa_cert, aa_privkey — the
// identity Google Android Auto accepts) to the AAP TLS handshake.
package io.motohub.android.aa

import android.util.Base64
import io.motohub.android.aaplugin.AaIdentityProvider
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

class SingleKeyKeyManager(certificate: X509Certificate, privateKey: PrivateKey) : X509ExtendedKeyManager() {

    constructor(identity: AaIdentityProvider) :
        this(createCertificate(identity), createPrivateKey(identity))

    private val delegate: X509KeyManager

    init {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        ks.setCertificateEntry(DEFAULT_ALIAS, certificate)
        ks.setKeyEntry(DEFAULT_ALIAS, privateKey, charArrayOf(), arrayOf(certificate))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        delegate = kmf.keyManagers[0] as X509KeyManager
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        delegate.getClientAliases(keyType, issuers)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        delegate.getServerAliases(keyType, issuers)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String =
        delegate.chooseServerAlias(keyType, issuers, socket)

    override fun getCertificateChain(alias: String?): Array<X509Certificate> =
        delegate.getCertificateChain(alias)

    override fun getPrivateKey(alias: String?): PrivateKey = delegate.getPrivateKey(alias)

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String =
        DEFAULT_ALIAS

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String =
        DEFAULT_ALIAS

    // X509ExtendedKeyManager's default returns null for server mode, so the SSLEngine presents no
    // certificate. In the phone role the head unit drives and MOTO-HUB is the TLS server, and a
    // server without a certificate fails the handshake at once ("Failure in SSL library", measured
    // on Vincenzo's head unit 2026-10-08). The Extend module carries the same fix.
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String =
        DEFAULT_ALIAS

    companion object {
        private const val DEFAULT_ALIAS = "defaultSingleKeyAlias"

        // The identity is the app's, staged into its own resources at build time; this only
        // borrows it. Whether it is there at all is the host's question to answer - see
        // AaIdentityProvider.isAvailable - so nothing here looks for a resource by name.
        private fun createCertificate(identity: AaIdentityProvider): X509Certificate =
            identity.openCertificate().use { certStream ->
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(certStream) as X509Certificate
            }

        private fun createPrivateKey(identity: AaIdentityProvider): PrivateKey {
            val privateKeyContent = identity.openIdentityData()
                .bufferedReader().use { it.readText() }
                .filterNot(Char::isWhitespace)
            val keySpecPKCS8 = PKCS8EncodedKeySpec(Base64.decode(privateKeyContent, Base64.DEFAULT))
            val kf = KeyFactory.getInstance("RSA")
            return kf.generatePrivate(keySpecPKCS8)
        }
    }
}
