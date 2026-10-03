// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import io.motohub.android.dashcam.net.CameraHttp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HiSilicon firmware: `http://<camera>/cgi-bin/hisnet/<name>.cgi?&-key=value` answering lines of
 * JavaScript (`var key="value";`), usually at 192.168.0.1, live video at
 * `rtsp://<camera>:554/livestream/1`. A client registers its own address before it is served.
 *
 * EXPERIMENTAL: written from the protocol and never run against a camera.
 */
object HisiliconProtocol : CameraProtocol {
    override val family = "HiSilicon"
    override val experimental = true
    override val defaultHosts = listOf("192.168.0.1")
    override val folders = listOf(
        CameraFolder("norm", "Videos"),
        CameraFolder("emr", "Locked"),
        CameraFolder("photo", "Photos")
    )

    private fun cgi(http: CameraHttp, name: String, args: String = ""): Map<String, String> {
        val response = http.get("/cgi-bin/hisnet/$name.cgi?$args")
        if (!response.ok) throw CameraException("The camera refused $name (HTTP ${response.code}).")
        return Answers.assignments(response.text)
    }

    override fun detect(http: CameraHttp): CameraIdentity? {
        val attr = runCatching { http.get("/cgi-bin/hisnet/getdeviceattr.cgi?", 3000) }.getOrNull() ?: return null
        if (!attr.ok || !attr.text.contains("var ")) return null
        val values = Answers.assignments(attr.text)
        return CameraIdentity(
            model = values["model"].orEmpty(),
            maker = values["company"].orEmpty(),
            firmware = values["softversion"].orEmpty(),
            details = listOfNotNull(values["hardversion"]?.let { "Hardware" to it })
        )
    }

    override fun logon(http: CameraHttp, phoneAddress: String?) {
        if (phoneAddress != null) runCatching { http.get("/cgi-bin/hisnet/client.cgi?&-operation=register&-ip=$phoneAddress") }
    }

    override fun logout(http: CameraHttp, phoneAddress: String?) {
        if (phoneAddress != null) runCatching { http.get("/cgi-bin/hisnet/client.cgi?&-operation=unregister&-ip=$phoneAddress") }
    }

    override fun heartbeat(http: CameraHttp) {
        cgi(http, "getdeviceattr")
    }

    override fun syncClock(http: CameraHttp) {
        runCatching { cgi(http, "setsystime", "&-time=" + SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())) }
    }

    override fun prepareLive(http: CameraHttp): String {
        runCatching { cgi(http, "setworkmode", "&-workmode=NORM_REC") }
        val channel = runCatching { cgi(http, "getcamchnl", "&-camid=0") }.getOrNull()
        channel?.values?.firstOrNull { it.startsWith("rtsp://", ignoreCase = true) }?.let { return it }
        return "rtsp://${http.host}:554/livestream/1"
    }

    override fun status(http: CameraHttp): CameraStatus {
        val state = runCatching { cgi(http, "getworkstate") }.getOrNull()
        val battery = runCatching { cgi(http, "getbatterycapacity") }.getOrNull()
        val card = runCatching { cgi(http, "getsdstatus") }.getOrNull()
        return CameraStatus(
            recording = state?.values?.any { it.equals("recording", ignoreCase = true) || it == "1" },
            batteryPercent = battery?.get("capacity")?.toIntOrNull(),
            charging = battery?.get("charge")?.let { it == "1" },
            cardReady = card?.get("sdstatus")?.let { it.equals("mount", ignoreCase = true) || it == "0" },
            cardFreeMb = card?.get("sdfreespace")?.toLongOrNull()?.let { it / 1024 },
            cardTotalMb = card?.get("sdtotalspace")?.toLongOrNull()?.let { it / 1024 }
        )
    }

    override fun setRecording(http: CameraHttp, on: Boolean) {
        cgi(http, "workmodecmd", "&-cmd=${if (on) "start" else "stop"}")
    }

    override fun lockClip(http: CameraHttp) {
        cgi(http, "workmodecmd", "&-cmd=emrbegin")
    }

    override fun snapshot(http: CameraHttp) {
        cgi(http, "workmodecmd", "&-cmd=trigger")
    }

    override fun settings(http: CameraHttp): List<CameraSetting> {
        val audio = runCatching { cgi(http, "getcommparam", "&-type=AUDIO").values.firstOrNull() }.getOrNull()
        val osd = runCatching { cgi(http, "getcamparam", "&-workmode=NORM_REC&-type=OSD").values.firstOrNull() }.getOrNull()
        val onOff = listOf("Off", "On")
        val codes = listOf("0", "1")
        return listOf(
            CameraSetting("AUDIO", "Microphone", onOff, codes, codes.indexOfFirst { it == audio }),
            CameraSetting("OSD", "Date and time on the video", onOff, codes, codes.indexOfFirst { it == osd })
        )
    }

    override fun changeSetting(http: CameraHttp, setting: CameraSetting, option: Int) {
        val value = setting.values[option]
        if (setting.key == "AUDIO") cgi(http, "setcommparam", "&-type=AUDIO&-value=$value")
        else cgi(http, "setcamparam", "&-workmode=NORM_REC&-type=${setting.key}&-value=$value")
    }

    override fun files(http: CameraHttp, folder: CameraFolder): List<CameraFile> {
        val count = runCatching { cgi(http, "getdirfilecount", "&-dir=${folder.id}").values.firstOrNull()?.toIntOrNull() }.getOrNull() ?: 0
        if (count <= 0) return emptyList()
        val text = http.text("/cgi-bin/hisnet/getdirfilelist.cgi?&-dir=${folder.id}&-start=0&-end=${count - 1}")
        return parseFiles(text, folder.id).sortedByDescending { it.createdMillis }
    }

    /** A semicolon-separated list of paths on the card, one per file; the name carries the time. */
    internal fun parseFiles(text: String, folderId: String): List<CameraFile> =
        text.split(';', '\n').map { it.trim().removePrefix("var ").substringAfter('=').trim('"', ' ') }
            .filter { it.contains('/') && it.contains('.') }
            .map { raw ->
                val path = if (raw.startsWith("/")) raw else "/$raw"
                val name = Answers.fileName(path)
                CameraFile(
                    path = path,
                    name = name,
                    folder = folderId,
                    sizeBytes = 0L,
                    createdMillis = Regex("(\\d{4})_?(\\d{2})_?(\\d{2})_?(\\d{2})_?(\\d{2})_?(\\d{2})").find(name)
                        ?.groupValues?.drop(1)?.joinToString("")?.let { Answers.parseTime(it, "yyyyMMddHHmmss") } ?: 0L,
                    durationSeconds = 0,
                    photo = Answers.isPhoto(path)
                )
            }

    override fun delete(http: CameraHttp, file: CameraFile) {
        cgi(http, "deletefile", "&-name=" + Answers.encodePath(file.path))
    }

    override fun formatCard(http: CameraHttp) {
        runCatching { setRecording(http, false) }
        cgi(http, "sdcommand", "&-format")
    }
}
