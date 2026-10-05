// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
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
    fun xmlValue(xml: String, tag: String): String? {
        val m = element(tag).matcher(xml)
        return if (m.find()) m.group(1)?.trim() else null
    }

    /** Every `<tag>…</tag>` block, for lists of files. */
    fun xmlBlocks(xml: String, tag: String): List<String> {
        val m = element(tag).matcher(xml)
        val out = ArrayList<String>()
        while (m.find()) out += m.group(1).orEmpty()
        return out
    }

    /*
     * java.util.regex, not kotlin.text.Regex. The module runs on the app's copy of the Kotlin
     * stdlib, which R8 has shrunk to what the app itself calls: dashcam 0.4.1 used
     * Regex(String, Set<RegexOption>), the APK no longer had that constructor, and the first
     * Novatek detect killed the app (NoSuchMethodError, 2026-10-05). The platform's regex is not
     * the app's to shrink.
     */
    private fun element(tag: String): Pattern =
        Pattern.compile("<$tag>(.*?)</$tag>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)

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
