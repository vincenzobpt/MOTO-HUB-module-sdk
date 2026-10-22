// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.mapopt

import io.motohub.android.module.ModuleMapboxAccount
import io.motohub.android.module.MotoHubModuleHost
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Properties

internal const val MAPBOX_STYLE = "mapbox/standard"

/** The rider's Mapbox token, sealed in the module's storage, its on/off switch, and the one call that checks it. */
internal class MapboxAccount(private val host: MotoHubModuleHost) : ModuleMapboxAccount {

    private val file = File(host.storage, "mapbox.properties")
    val usage = Usage(File(host.storage, "usage.properties"))

    @Volatile
    var token: String = ""
        private set

    /** Off keeps the token but gives the 3D POV the app's own map. */
    @Volatile
    var enabled: Boolean = true
        private set

    init {
        load()
    }

    override fun accessToken(): String? = if (enabled) token.ifBlank { null } else null

    override fun countMapLoad() = usage.add(Billed.MAP_LOADS)

    /** "pk.•••• 3F2A": enough to know which token it is. */
    val masked: String get() = if (token.isBlank()) "" else "pk.•••• " + token.takeLast(4)

    fun save(newToken: String) {
        token = newToken.trim()
        write()
    }

    fun forget() {
        token = ""
        write()
    }

    fun switchOn(on: Boolean) {
        enabled = on
        write()
    }

    /** Null when Mapbox accepts [candidate] for the style, else what to tell the rider. Blocks: call off the main thread. */
    fun check(candidate: String): String? {
        tokenProblem(candidate)?.let { return it }
        return try {
            val url = URL("https://api.mapbox.com/styles/v1/$MAPBOX_STYLE?access_token=" + URLEncoder.encode(candidate.trim(), "UTF-8"))
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            try {
                when (c.responseCode) {
                    200 -> null
                    401 -> "Mapbox does not know this token."
                    403 -> "Mapbox refused this token. If it has URL restrictions, remove them: an app has no URL."
                    else -> "Mapbox answered ${c.responseCode}. Try again in a moment."
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            "Mapbox could not be reached. Are you online?"
        }
    }

    private fun load() {
        if (!file.exists()) return
        val p = Properties()
        runCatching { file.inputStream().use { p.load(it) } }
        token = p.getProperty("tokenEnc", "").takeIf { it.isNotEmpty() }?.let { Secret.open(it) }.orEmpty()
        enabled = p.getProperty("enabled", "true") == "true"
    }

    private fun write() {
        val p = Properties()
        p.setProperty("tokenEnc", if (token.isBlank()) "" else Secret.seal(token))
        p.setProperty("enabled", enabled.toString())
        host.storage.mkdirs()
        file.outputStream().use { p.store(it, null) }
    }
}

/**
 * Null for something shaped like a public Mapbox token, else why not. Only a public one ("pk.")
 * may go into the 3D POV's web page: a secret one ("sk.") would be readable by its scripts.
 */
internal fun tokenProblem(candidate: String): String? {
    val t = candidate.trim()
    return when {
        t.isEmpty() -> "Paste your Mapbox token first."
        t.startsWith("sk.") -> "That is a secret token. Use a public one: it starts with pk."
        !t.startsWith("pk.") -> "A Mapbox public token starts with pk."
        t.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E } -> "The token has characters a token cannot have."
        else -> null
    }
}
