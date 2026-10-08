// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * The H.264 encoder the phone role projects through. The app draws the Ride Dashboard into
 * [inputSurface]; what comes out goes to the head unit as AAP media messages.
 *
 * The module owns this rather than borrowing the app's encoder because the wire format is the
 * module's: Android Auto wants the codec configuration as its own message and every frame with a
 * timestamp in front, which is nothing like the raw access units the app's AOA paths write.
 *
 * Settings, and why:
 *  - Baseline profile: the head unit asked for MEDIA_CODEC_VIDEO_H264_BP.
 *  - A one-second GOP and sync frames carrying their own SPS/PPS: a unit that lost a frame
 *    recovers within a second, and a decoder that missed the configuration message still finds it.
 *  - Repeat the previous frame after 100 ms: a surface encoder only emits when the picture changes,
 *    and many car decoders show a frame only once the next arrives, so a dashboard that stopped
 *    moving would sit one frame behind until something moved again.
 *  - Cap the input at the negotiated frame rate: a live map redraws faster than a head unit acks,
 *    and frames queued inside the encoder are frames the car sees late.
 */
internal class PhoneVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val log: (String) -> Unit,
    /** SPS/PPS, once, before the first frame. Annex-B. */
    private val onCodecConfig: (ByteArray) -> Unit,
    /** One access unit. May block: the caller waits for the head unit's acks here. */
    private val onFrame: (data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) -> Unit
) {
    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var worker: Thread? = null

    var inputSurface: Surface? = null
        private set

    fun start(): Surface {
        val encoder = configure()
        val surface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        inputSurface = surface
        running = true
        worker = Thread({ drain(encoder) }, "aa-phone-encoder").apply { isDaemon = true; start() }
        return surface
    }

    fun requestSyncFrame() {
        val c = codec ?: return
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            log("Encoder refused a sync frame request: ${e.message}")
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}
        codec = null
        inputSurface = null
    }

    private fun configure(): MediaCodec {
        val bitRate = (BASE_BIT_RATE.toLong() * width * height / (1280L * 720L))
            .toInt().coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)
        val level = when {
            width * height <= 1280 * 720 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            width * height <= 1920 * 1080 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel51
        }

        fun format(tuned: Boolean) = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US)
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            if (tuned) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, level)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }

        val tuned = MediaCodec.createEncoderByType(MIME)
        try {
            tuned.configure(format(tuned = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            log("Encoder: ${width}x$height @ ${fps}fps, ${bitRate / 1000} kbps, Baseline, 1 s GOP (${tuned.name}).")
            return tuned
        } catch (e: Exception) {
            // Some encoders refuse a hint they do not know; the plain format is what always works.
            log("Encoder refused the tuned format (${e.message}); using the plain one.")
            tuned.release()
        }
        val plain = MediaCodec.createEncoderByType(MIME)
        plain.configure(format(tuned = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        log("Encoder: ${width}x$height @ ${fps}fps, ${bitRate / 1000} kbps, default profile (${plain.name}).")
        return plain
    }

    private fun drain(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var configSent = false
        try {
            while (running) {
                val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Some encoders deliver SPS/PPS only here, as csd-0/csd-1, and never as a
                        // CODEC_CONFIG buffer. Send whichever comes first.
                        if (!configSent) {
                            csdFrom(encoder.outputFormat)?.let { onCodecConfig(it); configSent = true }
                        }
                    }
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(bytes)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                if (!configSent) { onCodecConfig(bytes); configSent = true }
                            } else {
                                onFrame(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                            }
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            // stop() released the codec under us: the ordinary way out.
            if (running) log("Encoder stopped unexpectedly: ${e.message}")
        } catch (e: Exception) {
            if (running) log("Encoder loop failed: ${e.message}")
        }
    }

    private fun csdFrom(format: MediaFormat): ByteArray? {
        val out = ByteArrayOutputStream()
        for (key in arrayOf("csd-0", "csd-1")) {
            val b = format.getByteBuffer(key) ?: continue
            val dup = b.duplicate()
            val bytes = ByteArray(dup.remaining())
            dup.get(bytes)
            out.write(bytes, 0, bytes.size)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        /** What the app's Ride Dashboard stream runs at 1280x720; scaled by pixel count. */
        private const val BASE_BIT_RATE = 4_194_304
        private const val MIN_BIT_RATE = 2_000_000
        private const val MAX_BIT_RATE = 12_000_000
        private const val I_FRAME_INTERVAL_S = 1
        private const val REPEAT_AFTER_US = 100_000L
        private const val DEQUEUE_TIMEOUT_US = 100_000L
    }
}
