// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONObject

/**
 * Reading what cameras answer. None of them speak quite what they claim to: the eeasytech one
 * answers an unknown command with `{result: 98}` (JSON without quotes), HiSilicon with lines of
 * JavaScript, Novatek with XML that is not always well formed. These are lenient on purpose.
 */
internal object Answers {
    /** org.json accepts unquoted keys, which is exactly what `{result: 98}` needs. */
    fun json(text: String): JSONObject? = runCatching { JSONObject(text.trim()) }.getOrNull()

    /** `<Tag>value</Tag>`, first occurrence, case-insensitive. */
    fun xmlValue(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.trim()

    /** Every `<tag>…</tag>` block, for lists of files. */
    fun xmlBlocks(xml: String, tag: String): List<String> =
        Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml).map { it.groupValues[1] }.toList()

    /** `var key="value";` lines (HiSilicon), and plain `key=value` lines (MStar). */
    fun assignments(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("var ").trimEnd(';')
            val eq = line.indexOf('=')
            if (eq > 0) out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim().trim('"')
        }
        return out
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** A path keeps its slashes; only what would break the query is escaped. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

    fun parseTime(text: String, vararg patterns: String): Long {
        for (p in patterns) {
            runCatching { SimpleDateFormat(p, Locale.ROOT).parse(text)?.time }.getOrNull()?.let { return it }
        }
        return 0L
    }

    fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    fun isPhoto(path: String): Boolean = path.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png")
}
