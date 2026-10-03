// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import io.motohub.android.dashcam.net.CameraHttp

/**
 * MStar / SigmaStar firmware: `http://<camera>/cgi-bin/Config.cgi?action=get|set|dir|del&property=…`
 * answering `0\nOK\nkey=value` lines, usually at 192.72.1.1, live video at
 * `rtsp://<camera>/liveRTSP/av4`.
 *
 * EXPERIMENTAL: written from the protocol and never run against a camera.
 */
object MstarProtocol : CameraProtocol {
    override val family = "MStar"
    override val experimental = true
    override val defaultHosts = listOf("192.72.1.1")
    override val folders = listOf(
        CameraFolder("Normal", "Videos"),
        CameraFolder("Event", "Events"),
        CameraFolder("Parking", "Parking"),
        CameraFolder("Photo", "Photos")
    )

    private fun config(http: CameraHttp, query: String): String {
        val response = http.get("/cgi-bin/Config.cgi?action=$query")
        val text = response.text
        if (!response.ok || text.lineSequence().firstOrNull()?.trim() != "0") {
            throw CameraException("The camera refused \"${query.substringBefore('&')}\".")
        }
        return text
    }

    private fun property(http: CameraHttp, name: String): String? =
        runCatching { Answers.assignments(config(http, "get&property=$name"))[name] }.getOrNull()

    override fun detect(http: CameraHttp): CameraIdentity? {
        val text = runCatching { http.text("/cgi-bin/Config.cgi?action=get&property=Net.WIFI_AP.SSID", 3000) }.getOrNull() ?: return null
        if (text.lineSequence().firstOrNull()?.trim() != "0") return null
        val ssid = Answers.assignments(text)["Net.WIFI_AP.SSID"].orEmpty()
        return CameraIdentity(model = ssid, maker = "", firmware = property(http, "Camera.Menu.FWversion").orEmpty(), details = emptyList())
    }

    override fun heartbeat(http: CameraHttp) {
        config(http, "get&property=Camera.Preview.MJPEG.status.record")
    }

    override fun prepareLive(http: CameraHttp): String = "rtsp://${http.host}/liveRTSP/av4"

    override fun status(http: CameraHttp): CameraStatus {
        val record = property(http, "Camera.Preview.MJPEG.status.record")
        val battery = property(http, "Camera.Menu.BatteryLevel")?.filter { it.isDigit() }?.toIntOrNull()
        val card = property(http, "Camera.Menu.SDInfo")
        return CameraStatus(
            recording = record?.let { it.equals("Recording", ignoreCase = true) },
            batteryPercent = battery?.takeIf { it in 0..100 },
            charging = null,
            cardReady = card?.let { !it.contains("NONE", ignoreCase = true) && !it.contains("ERR", ignoreCase = true) },
            cardFreeMb = null,
            cardTotalMb = null
        )
    }

    override fun setRecording(http: CameraHttp, on: Boolean) {
        // "record" toggles on this family: only send it when the state is not already the one asked for.
        val recording = property(http, "Camera.Preview.MJPEG.status.record")?.equals("Recording", ignoreCase = true)
        if (recording != on) config(http, "set&property=Video&value=record")
    }

    override fun lockClip(http: CameraHttp) {
        config(http, "set&property=Video&value=lock")
    }

    override fun snapshot(http: CameraHttp) {
        config(http, "set&property=Video&value=capture")
    }

    override fun settings(http: CameraHttp): List<CameraSetting> {
        val onOff = listOf("Off", "On")
        val codes = listOf("OFF", "ON")
        val sound = property(http, "Camera.Menu.SoundRecord")?.uppercase()
        val loop = property(http, "Camera.Menu.LoopingVideo")?.uppercase()
        val loopCodes = listOf("1MIN", "2MIN", "3MIN", "5MIN")
        return listOf(
            CameraSetting("SoundRecord", "Microphone", onOff, codes, codes.indexOfFirst { it == sound }),
            CameraSetting("LoopingVideo", "Length of each file", listOf("1 min", "2 min", "3 min", "5 min"), loopCodes, loopCodes.indexOfFirst { it == loop })
        )
    }

    override fun changeSetting(http: CameraHttp, setting: CameraSetting, option: Int) {
        config(http, "set&property=${setting.key}&value=${setting.values[option]}")
    }

    override fun files(http: CameraHttp, folder: CameraFolder): List<CameraFile> {
        val out = ArrayList<CameraFile>()
        var from = 0
        while (from < 2000) {
            val xml = runCatching { http.text("/cgi-bin/Config.cgi?action=dir&property=${folder.id}&format=all&count=50&from=$from") }.getOrNull() ?: break
            val page = parseFiles(xml, folder.id)
            out += page
            if (page.size < 50) break
            from += 50
        }
        return out.sortedByDescending { it.createdMillis }
    }

    /** `<file><name>/SD/Normal/F/….MP4</name><size>…</size><time>yyyy-MM-dd HH:mm:ss</time></file>` */
    internal fun parseFiles(xml: String, folderId: String): List<CameraFile> = Answers.xmlBlocks(xml, "file").mapNotNull { block ->
        val name = Answers.xmlValue(block, "name") ?: return@mapNotNull null
        val path = if (name.startsWith("/")) name else "/$name"
        CameraFile(
            path = path,
            name = Answers.fileName(path),
            folder = folderId,
            sizeBytes = Answers.xmlValue(block, "size")?.filter { it.isDigit() }?.toLongOrNull() ?: 0L,
            createdMillis = Answers.xmlValue(block, "time")?.let { Answers.parseTime(it, "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss") } ?: 0L,
            durationSeconds = 0,
            photo = Answers.isPhoto(path)
        )
    }

    override fun delete(http: CameraHttp, file: CameraFile) {
        // This family addresses a file with '$' for every '/'.
        config(http, "del&property=" + file.path.replace('/', '$'))
    }

    override fun formatCard(http: CameraHttp) {
        runCatching { setRecording(http, false) }
        config(http, "set&property=SD0&value=format")
    }
}
