// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Ported from headunit-revived (AGPLv3): ssl/NoCheckTrustManager.kt
//
// AAP's TLS is mutually authenticated and MOTO-HUB verifies nothing - that part is the port, and it
// stays. What is ours is that the chain no longer goes in the bin unread.
//
// As a head unit MOTO-HUB is the TLS *client*, so the peer of `checkServerTrusted` is Gearhead:
// every session hands us, in the clear, the certificate a real 2026 Android Auto phone presents.
// That is the one fact the phone-role probe cannot get any other way (see the
// phone-role probe notes). The only phone-role certificate in the open -
// AACS `AAServer/ssl/android_auto.crt`, `O=CarService, OU=53`, issued by `Google Automotive Link` -
// expired on 2022-08-24, and a head unit that checks `notAfter` will refuse it. Whether that
// certificate is merely stale or the scheme has moved on decides whether the phone role is hard or
// impossible, and Gearhead answers it for free on the next connection.
//
// Certificates are public by construction: this logs metadata only - names, dates, serial,
// algorithms - and never key material.
package io.motohub.android.aa

import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.X509TrustManager

class NoCheckTrustManager : X509TrustManager {

    /** Peer is the TLS client. MOTO-HUB is the server here, i.e. it is standing in for a phone. */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        report("TLS client", chain, authType)
    }

    /** Peer is the TLS server. On the receiver path that is Gearhead - a real phone identity. */
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        report("TLS server", chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate>? = null

    private fun report(peer: String, chain: Array<out X509Certificate>?, authType: String?) {
        try {
            if (chain.isNullOrEmpty()) {
                AaLog.i("Peer certificate ($peer, authType=$authType): none presented.")
                return
            }
            AaLog.i("Peer certificate ($peer, authType=$authType): ${chain.size} in chain.")
            chain.forEachIndexed { index, cert ->
                AaLog.i("  [$index] ${describe(cert)}")
            }
        } catch (e: Exception) {
            // Never let a diagnostic take down a handshake that is not supposed to fail.
            AaLog.w("Peer certificate ($peer): could not be described: ${e.message}")
        }
    }

    private companion object {
        fun describe(cert: X509Certificate): String {
            val notBefore = cert.notBefore
            val notAfter = cert.notAfter
            val now = Date()
            val validity = when {
                now.before(notBefore) -> "NOT YET VALID"
                now.after(notAfter) -> "EXPIRED"
                else -> "valid"
            }
            return "subject=${cert.subjectX500Principal.name}" +
                " issuer=${cert.issuerX500Principal.name}" +
                " serial=${cert.serialNumber.toString(16)}" +
                " notBefore=${fmt(notBefore)} notAfter=${fmt(notAfter)} ($validity)" +
                " sigAlg=${cert.sigAlgName} key=${keyOf(cert)}"
        }

        fun keyOf(cert: X509Certificate): String {
            val key = cert.publicKey
            val bits = when (key) {
                is RSAPublicKey -> key.modulus.bitLength()
                is ECPublicKey -> key.params?.order?.bitLength()
                else -> null
            }
            return if (bits != null) "${key.algorithm}/$bits" else key.algorithm
        }

        fun fmt(date: Date): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(date)
    }
}
