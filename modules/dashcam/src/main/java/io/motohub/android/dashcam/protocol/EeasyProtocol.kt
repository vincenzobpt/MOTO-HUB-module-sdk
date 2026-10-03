// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import io.motohub.android.dashcam.net.CameraHttp
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * eeasytech firmware (also sold as lombotech): `http://<camera>/app/<command>` answering JSON,
 * live video over RTSP on port 554. The family this module was built and tested on - a LINGTUO
 * "S1" battery dashcam, SSID `S1-xxxx`, at 192.168.169.1.
 *
 * What testing taught, which nothing documents:
 * - the RTSP server only runs while the camera RECORDS, and opens a few seconds after
 *   `enterrecorder` - so the live picture switches recording on, and asks again until it opens;
 * - port 5000 (the "port" in getmediainfo) is the camera's event channel, not video;
 * - an unknown command answers `{result: 98}`; a known one that fails, `{"result":1,"info":"set fail"}`;
 * - changing the codec stops recording and closes the RTSP port until recording starts again;
 * - its AAC audio track has an invalid header, so only video is ever set up;
 * - its RTSP server streams only the live picture: a recording asked for over RTSP gets an
 *   empty SDP. Recordings are read over HTTP (see CameraFileSource).
 */
object EeasyProtocol : CameraProtocol {
    override val family = "eeasytech"
    override val experimental = false
    override val liveNeedsRecording = true
    override val defaultHosts = listOf("192.168.169.1")
    override val folders = listOf(
        CameraFolder("loop", "Videos"),
        CameraFolder("event", "Events"),
        CameraFolder("emr", "Locked"),
        CameraFolder("park", "Parking"),
        CameraFolder("photo", "Photos")
    )

    private val labels = mapOf(
        "mic" to "Microphone",
        "osd" to "Date and time on the video",
        "logo_osd" to "Logo on the video",
        "video_flip" to "Flip the picture",
        "rec_split_duration" to "Length of each file",
        "encodec" to "Video codec",
        "language" to "Camera language",
        "rec_resolution" to "Resolution",
        "wdr" to "Wide dynamic range",
        "ev" to "Exposure",
        "boot_sound" to "Start-up sound",
        "screen_standby" to "Screen off after",
        "parking_monitor" to "Parking monitor",
        "gsensor" to "Impact sensitivity"
    )

    /** The settings that have their own button, not a row among the others. */
    private val notASetting = setOf("rec")

    private fun call(http: CameraHttp, command: String): JSONObject {
        val text = http.text("/app/$command")
        val json = Answers.json(text) ?: throw CameraException("The camera answered \"$command\" with something unreadable.")
        when (json.optInt("result", -1)) {
            0 -> return json
            98 -> throw CameraException("This camera does not know \"${command.substringBefore('?')}\".")
            else -> throw CameraException(
                "The camera refused \"${command.substringBefore('?')}\"" +
                    (json.optString("info").takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ".")
            )
        }
    }

    override fun detect(http: CameraHttp): CameraIdentity? {
        val attr = runCatching { Answers.json(http.text("/app/getdeviceattr", 3000)) }.getOrNull() ?: return null
        if (attr.optInt("result", -1) != 0) return null
        val info = attr.optJSONObject("info") ?: return null
        val product = runCatching { Answers.json(http.text("/app/getproductinfo", 3000))?.optJSONObject("info") }.getOrNull()
        val maker = product?.optString("sp").orEmpty().ifEmpty { product?.optString("company").orEmpty() }
        return CameraIdentity(
            model = info.optString("ssid").substringBefore('-').ifEmpty { product?.optString("model").orEmpty() },
            maker = maker,
            firmware = info.optString("softver"),
            details = listOfNotNull(
                product?.optString("soc")?.takeIf { it.isNotEmpty() }?.let { "Chipset" to it },
                info.optString("hwver").takeIf { it.isNotEmpty() }?.let { "Hardware" to it },
                info.optInt("camnum", 0).takeIf { it > 1 }?.let { "Cameras" to it.toString() }
            )
        )
    }

    override fun logon(http: CameraHttp, phoneAddress: String?) {
        call(http, "enterrecorder")
    }

    override fun logout(http: CameraHttp, phoneAddress: String?) {
        runCatching { call(http, "exitrecorder") }
    }

    override fun heartbeat(http: CameraHttp) {
        call(http, "getparamvalue?param=rec")
    }

    override fun syncClock(http: CameraHttp) {
        val now = Date()
        val hours = TimeZone.getDefault().getOffset(now.time) / 3_600_000
        runCatching { call(http, "settimezone?timezone=$hours") }
        runCatching { call(http, "setsystime?date=" + SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(now)) }
    }

    override fun prepareLive(http: CameraHttp): String {
        // The camera's own app asks these first, and the RTSP server came up reliably only after them.
        runCatching { call(http, "getproductinfo") }
        runCatching { call(http, "getdeviceattr") }
        val media = runCatching { call(http, "getmediainfo").optJSONObject("info") }.getOrNull()
        val url = media?.optString("rtsp")?.takeIf { it.startsWith("rtsp://", ignoreCase = true) } ?: "rtsp://${http.host}"
        val port = runCatching { URI(url).port }.getOrDefault(-1).takeIf { it > 0 } ?: 554

        if (recording(http) != true) {
            try {
                call(http, "setparamvalue?param=rec&value=1")
            } catch (e: CameraException) {
                if (recording(http) != true) throw CameraException(
                    "The camera will not start recording, and it only sends its picture while it records. " +
                        "Check that its SD card is in and formatted."
                )
            }
        }
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            runCatching { call(http, "enterrecorder") }
            if (http.reachable(port)) return url
            Thread.sleep(1500)
        }
        throw CameraException("The camera is recording but did not open its video stream. Switch it off and on, then try again.")
    }

    private fun recording(http: CameraHttp): Boolean? =
        runCatching { call(http, "getparamvalue?param=rec").optJSONObject("info")?.optInt("value") == 1 }.getOrNull()

    override fun status(http: CameraHttp): CameraStatus {
        val battery = runCatching { call(http, "getbatteryinfo").optJSONObject("info") }.getOrNull()
        val card = runCatching { call(http, "getsdinfo").optJSONObject("info") }.getOrNull()
        return parseStatus(recording(http), battery, card)
    }

    internal fun parseStatus(recording: Boolean?, battery: JSONObject?, card: JSONObject?): CameraStatus {
        val total = card?.optLong("total", -1L)?.takeIf { it >= 0 }
        return CameraStatus(
            recording = recording,
            batteryPercent = battery?.optInt("capacity", -1)?.takeIf { it in 0..100 },
            charging = battery?.takeIf { it.has("charge") }?.optInt("charge")?.let { it != 0 },
            // status 0 with a size is a card in use; status 1 or a size of 0 is no card the camera can use.
            cardReady = card?.let { it.optInt("status", -1) == 0 && (total ?: 0) > 0 },
            cardFreeMb = card?.optLong("free", -1L)?.takeIf { it >= 0 },
            cardTotalMb = total
        )
    }

    override fun setRecording(http: CameraHttp, on: Boolean) {
        call(http, "setparamvalue?param=rec&value=${if (on) 1 else 0}")
    }

    override fun lockClip(http: CameraHttp) {
        call(http, "lockvideo")
    }

    override fun snapshot(http: CameraHttp) {
        call(http, "snapshot")
    }

    override fun settings(http: CameraHttp): List<CameraSetting> =
        parseSettings(call(http, "getparamitems?param=all"), call(http, "getparamvalue?param=all"))

    internal fun parseSettings(items: JSONObject, values: JSONObject): List<CameraSetting> {
        val current = HashMap<String, String>()
        values.optJSONArray("info")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                current[o.optString("name")] = o.opt("value")?.toString().orEmpty()
            }
        }
        val out = ArrayList<CameraSetting>()
        val array: JSONArray = items.optJSONArray("info") ?: return out
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val key = o.optString("name")
            if (key.isEmpty() || key in notASetting) continue
            val names = o.optJSONArray("items")?.let { a -> List(a.length()) { a.optString(it) } } ?: continue
            val codes = o.optJSONArray("index")?.let { a -> List(a.length()) { a.opt(it).toString() } } ?: names.indices.map { it.toString() }
            if (names.size != codes.size || names.isEmpty()) continue
            out += CameraSetting(
                key = key,
                label = labels[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() },
                options = names.map(::prettyOption),
                values = codes,
                current = current[key].let { value -> codes.indexOfFirst { it == value } }
            )
        }
        return out
    }

    private fun prettyOption(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "on" -> "On"
        "off" -> "Off"
        "h.264" -> "H.264"
        "h.265" -> "H.265"
        else -> raw
    }

    override fun changeSetting(http: CameraHttp, setting: CameraSetting, option: Int) {
        call(http, "setparamvalue?param=${setting.key}&value=${setting.values[option]}")
    }

    /**
     * Playback mode, the way the camera's own app browses the card. Recording goes on: pausing it
     * here (tried in 0.1.4) cut the file being written short, and that file then failed to play
     * ("Invalid NAL length") - the rider chose that the camera keeps recording. The file still
     * being written is shown as such and not offered for playing.
     */
    override fun enterFiles(http: CameraHttp) {
        runCatching { call(http, "playback?param=enter") }
    }

    override fun exitFiles(http: CameraHttp) {
        runCatching { call(http, "playback?param=exit") }
        runCatching { call(http, "enterrecorder") }
    }

    override fun files(http: CameraHttp, folder: CameraFolder): List<CameraFile> {
        val out = ArrayList<CameraFile>()
        var start = 0
        // Pages of 100, as the camera's own app asks; stop at a short page.
        while (start < 2000) {
            val json = try {
                call(http, "getfilelist?folder=${folder.id}&start=$start&end=${start + 99}")
            } catch (e: CameraException) {
                // "get fail" is how this camera says a folder is empty or absent.
                break
            }
            val page = parseFiles(json, folder.id)
            out += page
            if (page.size < 100) break
            start += 100
        }
        return out.sortedByDescending { it.createdMillis }
    }

    /**
     * `{"result":0,"info":[{"folder":"loop","count":2,"files":[{"name":"/mnt/card/…mp4",
     * "createtime":…|"createtimestr":"yyyyMMddHHmmss","size":<KB>,"duration":<s>,"type":…}]}]}`;
     * `info` is sometimes a single object rather than an array.
     */
    internal fun parseFiles(json: JSONObject, folderId: String): List<CameraFile> {
        val groups = json.optJSONArray("info")
            ?: json.optJSONObject("info")?.let { JSONArray().put(it) }
            ?: return emptyList()
        val out = ArrayList<CameraFile>()
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val folder = group.optString("folder").ifEmpty { folderId }
            val files = group.optJSONArray("files") ?: continue
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val path = f.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val created = f.optString("createtimestr").takeIf { it.isNotEmpty() }
                    ?.let { Answers.parseTime(it, "yyyyMMddHHmmss") }
                    ?: (f.optLong("createtime", 0L) * 1000L)
                out += CameraFile(
                    path = if (path.startsWith("/")) path else "/$path",
                    name = Answers.fileName(path),
                    folder = folder,
                    sizeBytes = f.optLong("size", 0L) * 1024L,
                    createdMillis = created,
                    durationSeconds = f.optInt("duration", 0),
                    photo = Answers.isPhoto(path)
                )
            }
        }
        return out
    }

    override fun delete(http: CameraHttp, file: CameraFile) {
        call(http, "deletefile?file=" + Answers.encodePath(file.path))
    }

    override fun formatCard(http: CameraHttp) {
        // The camera's own app stops recording before it formats.
        runCatching { setRecording(http, false) }
        call(http, "sdformat?index=0")
    }
}
