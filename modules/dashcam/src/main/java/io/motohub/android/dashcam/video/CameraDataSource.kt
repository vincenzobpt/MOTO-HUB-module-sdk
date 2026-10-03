// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.motohub.android.dashcam.net.CameraHttp
import java.io.IOException

/**
 * ExoPlayer's way to the camera's files: `dashcam://camera/<path on the card>` read over a socket
 * bound to the camera's Wi-Fi.
 *
 * Not ExoPlayer's own HTTP source: that one goes through HttpURLConnection, which the app's
 * security config forbids for cleartext to anything but loopback, and which would leave through
 * the phone's default network rather than the camera's. ExoPlayer does the rest - it loads ahead
 * on its own thread into a buffer, and reopens at the right position when the camera drops a
 * connection - which is what the plain MediaPlayer could not.
 *
 * Only open-ended ranges are asked for: the LINGTUO S1 answers `bytes=a-b` one byte short.
 */
@UnstableApi
class CameraDataSource(private val http: CameraHttp, private val log: (String) -> Unit) : BaseDataSource(true) {
    private var stream: CameraHttp.Stream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("No file in ${dataSpec.uri}.")
        val from = dataSpec.position
        if (from > 0) calibrate(path)
        val ask = from + (rangeShift[http.host] ?: 0L)
        val headers = if (from > 0) mapOf("Range" to "bytes=$ask-") else emptyMap()
        val s = http.open(path, headers, READ_TIMEOUT_MS)
        if (s.code !in 200..299) {
            s.close()
            throw IOException("The camera would not send $path (HTTP ${s.code}).")
        }
        if (from > 0 && s.code == 200) {
            // The camera ignored the range and started from the beginning.
            var left = from
            val scratch = ByteArray(64 * 1024)
            while (left > 0) {
                val r = s.body.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                if (r < 0) { s.close(); throw IOException("The camera's file ended early.") }
                left -= r
            }
        }
        val total = s.headers["content-range"]?.substringAfterLast('/')?.toLongOrNull()
            ?: s.contentLength.takeIf { it >= 0 }?.let { it + if (s.code == 206) from else 0 }
        val available = total?.let { it - from } ?: C.LENGTH_UNSET.toLong()
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else available
        stream = s
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    /**
     * Whether this camera starts a range where it was asked to. Measured once per camera on a
     * real file: the bytes from `bytes=1024-` are compared with bytes 1024.. of the whole file.
     * The LINGTUO S1 answers explicit ranges one byte short, which made a shifted start the first
     * suspect when a reopened read came back as "Invalid NAL length"; a shift found here is put
     * right on every later range, and logged either way.
     */
    private fun calibrate(path: String) {
        if (rangeShift.containsKey(http.host)) return
        val shift = try {
            val whole = http.open(path, emptyMap(), READ_TIMEOUT_MS).use { CameraHttp.readExactly(it.body, 4096) }
            val part = http.open(path, mapOf("Range" to "bytes=1024-"), READ_TIMEOUT_MS).use { CameraHttp.readExactly(it.body, 1024) }
            (-8..8).firstOrNull { d -> (0 until 1024).all { i -> part[i] == whole[1024 + d + i] } }
        } catch (e: IOException) {
            log("play: range check on $path failed: ${e.message}")
            null
        }
        // A shift of d means the camera starts d bytes late: ask for d bytes earlier.
        rangeShift[http.host] = (shift?.let { -it } ?: 0).toLong()
        log("play: range check: " + when (shift) {
            null -> "no match within 8 bytes; asking for ranges as they are"
            0 -> "the camera starts ranges exactly where asked"
            else -> "the camera starts ranges $shift byte(s) off; correcting"
        })
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: throw IOException("The camera's file is not open.")
        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(remaining, length.toLong()).toInt()
        val r = s.body.read(buffer, offset, toRead)
        if (r < 0) {
            if (remaining != C.LENGTH_UNSET.toLong()) throw IOException("The camera stopped sending the file.")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= r
        bytesTransferred(r)
        return r
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        stream?.close()
        stream = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val http: CameraHttp, private val log: (String) -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = CameraDataSource(http, log)
    }

    companion object {
        const val SCHEME = "dashcam"

        /** Per camera address: how many bytes to add to a range's start. Measured by [calibrate]. */
        private val rangeShift = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** The URI ExoPlayer is given for a file at [path] on the camera's card. */
        fun uriFor(path: String): Uri = Uri.Builder().scheme(SCHEME).authority("camera").path(path).build()

        private const val READ_TIMEOUT_MS = 8000
    }
}
