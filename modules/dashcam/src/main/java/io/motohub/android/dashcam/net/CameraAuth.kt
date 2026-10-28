// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.regex.Pattern

/** The camera's own login - not its Wi-Fi password. Never written to the log. */
class Credentials(val user: String, val password: String)

/**
 * The camera wants a login: none was given, or ([rejected]) the one given was turned down.
 * An IOException, so every caller that already copes with a camera saying no copes with this too.
 */
class LoginRequired(val rejected: Boolean) : IOException(
    if (rejected) "The camera did not accept the user name and password."
    else "The camera asks for a user name and password."
)

/**
 * The answer to one `WWW-Authenticate` challenge, for HTTP and RTSP alike: Basic (RFC 7617), or
 * Digest (RFC 7616) with MD5 or SHA-256, with or without `qop=auth`. A Digest answer counts its
 * requests, so one Authenticator serves one connection's run of requests.
 *
 * IP cameras ask for one or the other on their RTSP port and, for ONVIF, on HTTP; the Wi-Fi
 * dashcams this module started with ask for neither, and never see it.
 */
class Authenticator(
    private val credentials: Credentials,
    challenge: String,
    private val cnonce: String = newCnonce()
) {
    private val digest = challenge.trim().startsWith("digest", ignoreCase = true)
    private val params = parseChallenge(challenge.trim().substringAfter(' ', ""))
    private var count = 0

    /** The value of the `Authorization` header for [method] on [uri] (the request line's target). */
    fun header(method: String, uri: String): String {
        if (!digest) {
            val pair = "${credentials.user}:${credentials.password}".toByteArray(Charsets.UTF_8)
            return "Basic " + Base64.getEncoder().encodeToString(pair)
        }
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val algorithm = params["algorithm"] ?: "MD5"
        val hashName = if (algorithm.uppercase(Locale.ROOT).startsWith("SHA-256")) "SHA-256" else "MD5"
        var ha1 = hash(hashName, "${credentials.user}:$realm:${credentials.password}")
        if (algorithm.endsWith("-sess", ignoreCase = true)) ha1 = hash(hashName, "$ha1:$nonce:$cnonce")
        val ha2 = hash(hashName, "$method:$uri")
        // auth-int would hash the body; nothing here sends one that matters, and cameras offer auth.
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", ignoreCase = true) }
        val nc = String.format(Locale.ROOT, "%08x", ++count)
        val response = if (qop != null) hash(hashName, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else hash(hashName, "$ha1:$nonce:$ha2")
        return buildString {
            append("Digest username=\"").append(credentials.user).append('"')
            append(", realm=\"").append(realm).append('"')
            append(", nonce=\"").append(nonce).append('"')
            append(", uri=\"").append(uri).append('"')
            append(", response=\"").append(response).append('"')
            params["algorithm"]?.let { append(", algorithm=").append(it) }
            params["opaque"]?.let { append(", opaque=\"").append(it).append('"') }
            if (qop != null) append(", qop=").append(qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
        }
    }

    companion object {
        private val PARAM = Pattern.compile("([A-Za-z-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]*))")

        internal fun parseChallenge(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            val m = PARAM.matcher(text)
            while (m.find()) out[m.group(1)?.lowercase(Locale.ROOT) ?: continue] = m.group(2) ?: m.group(3).orEmpty()
            return out
        }

        internal fun hash(algorithm: String, text: String): String = hex(MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)))

        internal fun hex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append(HEX[v ushr 4]).append(HEX[v and 15])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"

        private fun newCnonce(): String = hex(ByteArray(8).also { SecureRandom().nextBytes(it) })

        /**
         * Of several `WWW-Authenticate` headers, the one to answer: Digest over Basic, which would
         * send the password readable to anyone on the network. Used where headers are read into a map.
         */
        internal fun keepChallenge(existing: String?, incoming: String): Boolean =
            existing == null || !existing.trim().startsWith("digest", ignoreCase = true)
    }
}
