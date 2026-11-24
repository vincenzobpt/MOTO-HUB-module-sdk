// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The audio half of a Paddock call (VOICE.md): microphone, Opus through Android's own MediaCodec,
 * a jitter buffer, the speaker. The network half is Python's; this class only sees opaque
 * packets: [send] takes each one we make, [receive] gets each one that arrived.
 *
 * Our packet: u16 sequence, u16 index of its first frame (20 ms units), u8 frame count, then for
 * each frame a u8 length and the Opus bytes. Two frames per packet: 25 packets a second.
 */
internal class VoiceAudio(
    private val context: Context,
    private val send: (ByteArray) -> Unit,
    private val log: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var modeBefore = AudioManager.MODE_NORMAL
    private var capture: Thread? = null
    private var playback: Thread? = null
    @Volatile var muted = false
    @Volatile private var speaker = false

    // Receive side, guarded by [jitter].
    private val jitter = java.util.TreeMap<Int, ByteArray>()
    private var playIndex = -1
    private var target = START_FRAMES

    fun start(speakerOn: Boolean) {
        if (!running.compareAndSet(false, true)) return
        speaker = speakerOn
        audio?.let {
            modeBefore = it.mode
            it.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        route()
        synchronized(jitter) { jitter.clear(); playIndex = -1; target = START_FRAMES }
        capture = Thread(::captureLoop, "paddock-mic").apply { start() }
        playback = Thread(::playLoop, "paddock-speaker").apply { start() }
        log("voice audio started")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        capture?.join(500)
        playback?.join(500)
        capture = null
        playback = null
        runCatching { audio?.clearCommunicationDevice() }
        audio?.mode = modeBefore
        log("voice audio stopped")
    }

    fun setSpeaker(on: Boolean) {
        speaker = on
        if (running.get()) route()
    }

    /** A helmet headset first, then the loudspeaker or the earpiece as the rider chose. */
    private fun route() {
        val am = audio ?: return
        val devices = am.availableCommunicationDevices
        val headset = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val pick = when {
            headset != null && !speaker -> headset
            speaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        runCatching { if (pick != null) am.setCommunicationDevice(pick) }
    }

    /** True when a helmet or other headset carries the call. */
    fun headsetConnected(): Boolean = audio?.availableCommunicationDevices?.any {
        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    } == true

    // ------------------------------------------------------------ send

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 8)
            )
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            log("microphone unavailable")
            rec?.release()
            return
        }
        // The phone's own tuned echo canceller, noise suppressor and gain, where it has them.
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId) else null
        ).onEach { runCatching { it.enabled = true } }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        enc.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            },
            null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        enc.start()
        rec.startRecording()
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        val pending = ArrayList<ByteArray>(FRAMES_PER_PACKET)
        var seq = 0
        var frameIndex = 0
        var pts = 0L
        var silentSince = 0L
        try {
            while (running.get()) {
                var got = 0
                while (got < FRAME_BYTES && running.get()) {
                    val n = rec.read(pcm, got, FRAME_BYTES - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME_BYTES) continue
                if (muted) java.util.Arrays.fill(pcm, 0.toByte()) // ArraysKt.fill is shrunk out of the app
                // Some phones hand out a dead microphone (all zeros) until it is opened again.
                if (!muted && pcm.all { it == 0.toByte() }) {
                    if (silentSince == 0L) silentSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - silentSince > 2000) {
                        log("microphone gives only silence; opening it again")
                        rec.stop(); rec.startRecording(); silentSince = 0L
                    }
                } else silentSince = 0L
                val inIx = enc.dequeueInputBuffer(10_000)
                if (inIx >= 0) {
                    enc.getInputBuffer(inIx)?.apply { clear(); put(pcm) }
                    enc.queueInputBuffer(inIx, 0, FRAME_BYTES, pts, 0)
                    pts += FRAME_MICROS
                }
                while (true) {
                    val outIx = enc.dequeueOutputBuffer(info, 0)
                    if (outIx < 0) break
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size in 1..255) {
                        val frame = ByteArray(info.size)
                        enc.getOutputBuffer(outIx)?.apply { position(info.offset); get(frame) }
                        pending.add(frame)
                    }
                    enc.releaseOutputBuffer(outIx, false)
                    if (pending.size == FRAMES_PER_PACKET) {
                        send(packet(seq, frameIndex, pending))
                        seq = (seq + 1) and 0xFFFF
                        frameIndex = (frameIndex + pending.size) and 0xFFFF
                        pending.clear()
                    }
                }
            }
        } catch (t: Throwable) {
            log("microphone stopped: ${t.javaClass.simpleName}")
        } finally {
            runCatching { rec.stop() }
            rec.release()
            effects.forEach { runCatching { it.release() } }
            runCatching { enc.stop() }
            enc.release()
        }
    }

    private fun packet(seq: Int, first: Int, frames: List<ByteArray>): ByteArray {
        val size = 5 + frames.sumOf { 1 + it.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        b.putShort(seq.toShort()).putShort(first.toShort()).put(frames.size.toByte())
        frames.forEach { b.put(it.size.toByte()).put(it) }
        return b.array()
    }

    // ------------------------------------------------------------ receive

    /** One packet from the network: its frames go into the jitter buffer by index. */
    fun receive(p: ByteArray) {
        if (p.size < 5 || !running.get()) return
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        b.short // sequence: the frame index already orders and dedups
        val first = b.short.toInt() and 0xFFFF
        val count = b.get().toInt() and 0xFF
        synchronized(jitter) {
            for (i in 0 until count) {
                if (b.remaining() < 1) return
                val len = b.get().toInt() and 0xFF
                if (b.remaining() < len) return
                val f = ByteArray(len).also { b.get(it) }
                val ix = (first + i) and 0xFFFF
                // Too late: that moment has been played already.
                if (playIndex >= 0 && behind(ix, playIndex)) continue
                jitter[unwrap(ix)] = f
            }
            while (jitter.size > MAX_FRAMES) jitter.pollFirstEntry()
        }
    }

    /** Frame indexes wrap at 65536 (22 minutes): map them onto a line that does not. */
    private fun unwrap(ix: Int): Int {
        if (playIndex < 0) return ix
        val cur = playIndex
        var v = (cur and 0xFFFF.inv()) or ix
        if (v < cur - 0x8000) v += 0x10000 else if (v > cur + 0x8000) v -= 0x10000
        return v
    }

    private fun behind(ix: Int, play: Int): Boolean = unwrap(ix) < play

    private fun playLoop() {
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        dec.configure(opusFormat(), null, null, 0)
        dec.start()
        var track: AudioTrack? = null
        var outRate = RATE
        val info = MediaCodec.BufferInfo()
        var lastPcm: ByteArray? = null
        var missesInRow = 0
        var pts = 0L
        fun ensureTrack(rate: Int): AudioTrack {
            track?.let { if (outRate == rate) return it; it.release() }
            outRate = rate
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 25 * 2 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            return t
        }
        try {
            ensureTrack(RATE)
            while (running.get()) {
                val frame: ByteArray?
                synchronized(jitter) {
                    if (playIndex < 0) {
                        // Start once the buffer holds the target depth.
                        if (jitter.size < target) frame = null
                        else {
                            playIndex = jitter.firstKey()
                            frame = jitter.remove(playIndex)
                            playIndex++
                        }
                    } else {
                        // Too far behind the newest frame: skip ahead instead of growing the delay.
                        val newest = if (jitter.isEmpty()) playIndex else jitter.lastKey()
                        while (newest - playIndex > target + DRIFT_FRAMES) {
                            jitter.remove(playIndex); playIndex++
                        }
                        frame = jitter.remove(playIndex)
                        if (frame != null || jitter.isNotEmpty()) playIndex++
                    }
                }
                if (playIndex < 0) { Thread.sleep(FRAME_MILLIS); continue }
                if (frame == null) {
                    missesInRow++
                    // A gap: the last sound once, softer, then silence. More gaps: a deeper buffer.
                    val fill = if (missesInRow == 1 && lastPcm != null) fade(lastPcm!!) else ByteArray(outRate / 50 * 2)
                    ensureTrack(outRate).write(fill, 0, fill.size)
                    if (missesInRow == 3) synchronized(jitter) { target = minOf(target + 2, MAX_TARGET) }
                    if (missesInRow > 25) synchronized(jitter) { playIndex = -1 } // the other side went quiet
                    continue
                }
                missesInRow = 0
                val inIx = dec.dequeueInputBuffer(10_000)
                if (inIx >= 0) {
                    dec.getInputBuffer(inIx)?.apply { clear(); put(frame) }
                    dec.queueInputBuffer(inIx, 0, frame.size, pts, 0)
                    pts += FRAME_MICROS
                }
                while (true) {
                    val outIx = dec.dequeueOutputBuffer(info, 5_000)
                    if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ensureTrack(dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        continue
                    }
                    if (outIx < 0) break
                    if (info.size > 0) {
                        val pcm = ByteArray(info.size)
                        dec.getOutputBuffer(outIx)?.apply { position(info.offset); get(pcm) }
                        lastPcm = pcm
                        ensureTrack(outRate).write(pcm, 0, pcm.size) // blocking: paces this loop
                    }
                    dec.releaseOutputBuffer(outIx, false)
                }
            }
        } catch (t: Throwable) {
            log("speaker stopped: ${t.javaClass.simpleName}")
        } finally {
            runCatching { track?.stop() }
            track?.release()
            runCatching { dec.stop() }
            dec.release()
        }
    }

    private fun fade(pcm: ByteArray): ByteArray {
        val out = ByteArray(pcm.size)
        val n = pcm.size / 2
        for (i in 0 until n) {
            val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            val g = 0.5 * (1.0 - i.toDouble() / n)
            val v = (s * g).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** What the Opus decoder needs before the first frame: an OpusHead and its two delays. */
    private fun opusFormat(): MediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray()).put(1).put(1).putShort(PRE_SKIP.toShort()).putInt(RATE).putShort(0).put(0)
        setByteBuffer("csd-0", ByteBuffer.wrap(head.array()))
        setByteBuffer("csd-1", nanos(PRE_SKIP * 1_000_000_000L / 48_000))
        setByteBuffer("csd-2", nanos(80_000_000L))
    }

    private fun nanos(v: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }

    private companion object {
        const val RATE = 16_000
        const val BITRATE = 16_000
        const val FRAME_MILLIS = 20L
        const val FRAME_MICROS = 20_000L
        const val FRAME_BYTES = RATE / 50 * 2 // 20 ms of 16-bit mono
        const val FRAMES_PER_PACKET = 2
        const val START_FRAMES = 6 // 120 ms before the first sound
        const val MAX_TARGET = 20 // 400 ms at most
        const val DRIFT_FRAMES = 6
        const val MAX_FRAMES = 60
        const val PRE_SKIP = 312
    }
}
