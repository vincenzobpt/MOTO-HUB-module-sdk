// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.probe

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * What a camera's answers say about the rider, taken out before they reach the log: passwords
 * and keys, serial numbers and device ids, GPS positions, and the second half of every MAC
 * address. The first half stays - it names the maker of the Wi-Fi chip, which is often what
 * tells one camera family from another. Make, model and firmware stay: that is what support for
 * a camera is built from.
 *
 * Keys are judged by name, in every shape a camera writes them: JSON, XML, `key=value`, and the
 * `var key="value"` some firmwares serve as JavaScript. Over-masking is the safe side of the line.
 */
internal object Redact {
    private const val HIDDEN = "***"

    private val JSON_STRING = Pattern.compile("\"([\\w.-]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val JSON_NUMBER = Pattern.compile("\"([\\w.-]+)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
    private val JS_STRING = Pattern.compile("\\b(\\w+)\\s*:\\s*'([^']*)'|\\b(\\w+)\\s*:\\s*\"([^\"]*)\"")
    private val XML = Pattern.compile("<((?:[\\w-]+:)?([\\w-]+))(\\s[^>]*)?>([^<]*)</\\1>")
    private val ASSIGN = Pattern.compile("\\b([\\w.-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^&;,\\s\"'<>]+)")
    private val MAC = Pattern.compile("\\b([0-9A-Fa-f]{2})([:-])([0-9A-Fa-f]{2})\\2([0-9A-Fa-f]{2})\\2[0-9A-Fa-f]{2}\\2[0-9A-Fa-f]{2}\\2[0-9A-Fa-f]{2}\\b")
    private val COORDINATES = Pattern.compile("-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{4,}")
    private val NMEA = Pattern.compile("[$]G[PNLAB][A-Z]{3},\\S*")
    private val SSID_SUFFIX = Pattern.compile("([_-]?)([0-9A-Fa-f]{4,})$")

    /** Parts of a key's name that make its value private, wherever they sit in it. */
    private val PRIVATE_PARTS = listOf(
        "pass", "pwd", "psk", "secret", "token", "serial", "sernum", "imei", "imsi", "iccid", "uuid",
        "license", "auth", "cookie", "session", "email", "phone"
    )

    /** Whole key names that are private, too short to look for as parts. */
    private val PRIVATE_KEYS = setOf(
        "key", "sn", "wifikey", "wpakey", "lat", "lon", "lng", "latitude", "longitude", "gps", "location", "position",
        "coord", "coordinates", "deviceid", "devid", "uid", "p2pid", "cloudid", "nonce"
    )

    fun text(input: String): String {
        var s = NMEA.each(input) { HIDDEN }
        s = COORDINATES.each(s) { "$HIDDEN,$HIDDEN" }
        s = MAC.each(s) { m ->
            val sep = m.g(2)
            m.g(1) + sep + m.g(3) + sep + m.g(4) + sep + "**" + sep + "**" + sep + "**"
        }
        s = JSON_STRING.each(s) { m -> "\"${m.g(1)}\":\"${value(m.g(1), m.g(2))}\"" }
        s = JSON_NUMBER.each(s) { m -> "\"${m.g(1)}\":${value(m.g(1), m.g(2))}" }
        s = JS_STRING.each(s) { m ->
            if (m.group(1) != null) "${m.g(1)}: '${value(m.g(1), m.g(2))}'" else "${m.g(3)}: \"${value(m.g(3), m.g(4))}\""
        }
        s = XML.each(s) { m -> "<${m.g(1)}${m.g(3)}>${value(m.g(2), m.g(4))}</${m.g(1)}>" }
        s = ASSIGN.each(s) { m ->
            val raw = m.g(2)
            val quote = if (raw.length >= 2 && (raw[0] == '"' || raw[0] == '\'') && raw[raw.length - 1] == raw[0]) raw[0].toString() else ""
            val inner = if (quote.isEmpty()) raw else raw.substring(1, raw.length - 1)
            "${m.g(1)}=$quote${value(m.g(1), inner)}$quote"
        }
        return s
    }

    /** [value] as it may be logged under [key]. */
    internal fun value(key: String, value: String): String {
        // Already masked by the patterns that run first (a MAC, a position): left as it is.
        if (value.isEmpty() || value.indexOf('*') >= 0) return value
        val k = key.lowercase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "")
        return when {
            k.contains("mac") || k == "bssid" -> halfMac(value)
            k == "ssid" || k.endsWith("ssid") -> ssid(value)
            k in PRIVATE_KEYS || PRIVATE_PARTS.any { k.contains(it) } -> HIDDEN
            else -> value
        }
    }

    /** A MAC written any way: the maker's half kept, the device's half hidden. */
    private fun halfMac(value: String): String {
        val hex = StringBuilder()
        for (c in value) if (Character.digit(c, 16) >= 0) hex.append(c)
        if (hex.length != 12) return HIDDEN
        return hex.substring(0, 6) + "******"
    }

    /** A network name, with the run of hex many cameras end it in - often their MAC's tail - hidden. */
    fun ssid(value: String): String = SSID_SUFFIX.each(value) { m ->
        val out = StringBuilder(m.g(1))
        for (i in 0 until m.g(2).length) out.append('*')
        out.toString()
    }

    /** A group's text; empty for a group that did not take part in the match. */
    private fun Matcher.g(i: Int): String = group(i).orEmpty()

    private fun Pattern.each(input: String, transform: (Matcher) -> String): String {
        val m = matcher(input)
        val out = StringBuffer()
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(transform(m)))
        m.appendTail(out)
        return out.toString()
    }
}
