// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import io.motohub.android.dashcam.net.CameraHttp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Novatek firmware: `http://<camera>/?custom=1&cmd=<number>[&par=<n>][&str=<s>]` answering XML
 * (`<Function><Cmd>…</Cmd><Status>0</Status>…</Function>`), usually at 192.168.1.254, live video
 * at `rtsp://<camera>/xxx.mov`.
 *
 * EXPERIMENTAL: written from the protocol and never run against a camera. The command numbers
 * are the family's well-known ones; a camera that differs answers with a negative status, which
 * the rider reads as a refusal rather than the module guessing.
 */
object NovatekProtocol : CameraProtocol {
    override val family = "Novatek"
    override val experimental = true
    override val defaultHosts = listOf("192.168.1.254")
    override val folders = listOf(CameraFolder("all", "Files"))

    private fun cmd(http: CameraHttp, number: Int, extra: String = ""): String {
        val xml = http.text("/?custom=1&cmd=$number$extra")
        val status = Answers.xmlValue(xml, "Status")?.toIntOrNull()
            ?: throw CameraException("The camera answered command $number with something unreadable.")
        if (status < 0) throw CameraException("The camera refused command $number (status $status).")
        return xml
    }

    override fun detect(http: CameraHttp): CameraIdentity? {
        val xml = runCatching { http.text("/?custom=1&cmd=3012", 3000) }.getOrNull() ?: return null
        if (Answers.xmlValue(xml, "Cmd") != "3012") return null
        val version = Answers.xmlValue(xml, "String").orEmpty()
        return CameraIdentity(model = "", maker = "", firmware = version, details = emptyList())
    }

    override fun heartbeat(http: CameraHttp) {
        cmd(http, 3016)
    }

    override fun syncClock(http: CameraHttp) {
        val now = Date()
        runCatching { cmd(http, 3005, "&str=" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now)) }
        runCatching { cmd(http, 3006, "&str=" + SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(now)) }
    }

    override fun prepareLive(http: CameraHttp): String {
        runCatching { cmd(http, 3001, "&par=1") } // movie mode
        runCatching { cmd(http, 2015, "&par=1") } // live view on
        return "rtsp://${http.host}/xxx.mov"
    }

    override fun status(http: CameraHttp): CameraStatus {
        val recording = runCatching { Answers.xmlValue(cmd(http, 2016), "Value")?.toIntOrNull()?.let { it > 0 } }.getOrNull()
        // Battery is a level, 0 = full to 5 = empty, on the cameras that report one at all.
        val battery = runCatching { Answers.xmlValue(cmd(http, 3019), "Value")?.toIntOrNull()?.let { (5 - it.coerceIn(0, 5)) * 20 } }.getOrNull()
        val card = runCatching { Answers.xmlValue(cmd(http, 3024), "Value")?.toIntOrNull() }.getOrNull()
        val freeBytes = runCatching { Answers.xmlValue(cmd(http, 3017), "Value")?.toLongOrNull() }.getOrNull()
        return CameraStatus(recording, battery, null, card?.let { it == 1 }, freeBytes?.let { it / (1024 * 1024) }, null)
    }

    override fun setRecording(http: CameraHttp, on: Boolean) {
        cmd(http, 2001, "&par=${if (on) 1 else 0}")
    }

    override fun lockClip(http: CameraHttp) {
        cmd(http, 9133, "&par=1")
    }

    override fun snapshot(http: CameraHttp) {
        try { cmd(http, 2017) } catch (e: CameraException) { cmd(http, 1001) }
    }

    private val known = listOf(
        Triple(2007, "Microphone", listOf("Off" to "0", "On" to "1")),
        Triple(2008, "Date and time on the video", listOf("Off" to "0", "On" to "1")),
        Triple(2003, "Length of each file", listOf("Off" to "0", "3 min" to "1", "5 min" to "2", "10 min" to "3"))
    )

    override fun settings(http: CameraHttp): List<CameraSetting> {
        val xml = cmd(http, 3014)
        // The answer pairs each command with its value: <Cmd>2007</Cmd><Status>1</Status>.
        val values = Regex("<Cmd>(\\d+)</Cmd>\\s*<Status>(-?\\d+)</Status>").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
        return known.map { (number, label, options) ->
            CameraSetting(number.toString(), label, options.map { it.first }, options.map { it.second },
                options.indexOfFirst { it.second == values[number.toString()] })
        }
    }

    override fun changeSetting(http: CameraHttp, setting: CameraSetting, option: Int) {
        cmd(http, setting.key.toInt(), "&par=${setting.values[option]}")
    }

    override fun enterFiles(http: CameraHttp) {
        runCatching { cmd(http, 3001, "&par=2") } // playback mode
    }

    override fun exitFiles(http: CameraHttp) {
        runCatching { cmd(http, 3001, "&par=1") }
    }

    override fun files(http: CameraHttp, folder: CameraFolder): List<CameraFile> =
        parseFiles(cmd(http, 3015)).sortedByDescending { it.createdMillis }

    /** `<File><NAME>…</NAME><FPATH>A:\CARDV\MOVIE\…MP4</FPATH><SIZE>…</SIZE><TIME>yyyy/MM/dd HH:mm:ss</TIME></File>` */
    internal fun parseFiles(xml: String): List<CameraFile> = Answers.xmlBlocks(xml, "File").mapNotNull { block ->
        val device = Answers.xmlValue(block, "FPATH") ?: return@mapNotNull null
        val path = "/" + device.substringAfter(":").replace('\\', '/').trimStart('/')
        CameraFile(
            path = path,
            name = Answers.xmlValue(block, "NAME") ?: Answers.fileName(path),
            folder = path.substringBeforeLast('/').substringAfterLast('/'),
            sizeBytes = Answers.xmlValue(block, "SIZE")?.toLongOrNull() ?: 0L,
            createdMillis = Answers.xmlValue(block, "TIME")?.let { Answers.parseTime(it, "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm") } ?: 0L,
            durationSeconds = 0,
            photo = Answers.isPhoto(path)
        )
    }

    override fun delete(http: CameraHttp, file: CameraFile) {
        val device = "A:" + file.path.replace('/', '\\')
        cmd(http, 4003, "&str=" + Answers.encode(device))
    }

    override fun formatCard(http: CameraHttp) {
        runCatching { setRecording(http, false) }
        cmd(http, 3010, "&par=1")
    }
}
