// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.net.Socket
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the live picture is doing, for the screen to show. */
data class LiveState(
    val status: String = "Starting…",
    val playing: Boolean = false,
    val fps: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val codec: VideoCodec? = null
)

/**
 * The camera's live picture, decoded onto a Surface for as long as [start] to [stop].
 *
 * One thread does everything in order - ask the camera for its stream, RTSP, depacketise,
 * decode - because each step only makes sense after the one before, and a single owner of the
 * decoder is simpler than handing it between threads. If the camera drops the stream, the thread
 * asks again after a pause; the rider sees why in [state] rather than a frozen frame.
 *
 * Frames are rendered the moment they are decoded, never scheduled by timestamp: a live view that
 * waits for its own clock is a live view that is late.
 */
class LivePlayer(
    /**
     * Readies the camera and returns its RTSP URL, given how many sessions in a row ended without
     * a picture. Blocking; throws with a reason a rider can read.
     */
    private val prepare: (misses: Int) -> String,
    private val bind: (Socket) -> Unit,
    private val log: (String) -> Unit,
    /** Called once per RTSP session, when its first picture is on screen. */
    private val onFirstPicture: () -> Unit = {}
) {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceVersion = 0
    @Volatile private var running = false
    @Volatile private var client: RtspClient? = null
    private var thread: Thread? = null

    /**
     * Which thread is the live one. A stop followed at once by a start (the files panel opened and
     * closed) must not leave the old thread running beside the new one just because it sees
     * [running] true again: each thread checks that it is still the current generation.
     */
    @Volatile private var generation = 0

    /** Whether the current session has put a picture on screen. */
    @Volatile private var pictured = false

    fun setSurface(s: Surface?) {
        surface = s
        surfaceVersion++
    }

    fun start() {
        if (running) return
        running = true
        val mine = ++generation
        thread = Thread({ loop(mine) }, "dashcam-live").apply { start() }
    }

    fun stop() {
        running = false
        generation++
        client?.close()
        thread?.interrupt()
        thread = null
    }

    private fun isCurrent(mine: Int) = running && generation == mine

    private fun loop(mine: Int) {
        var attempt = 0
        var misses = 0
        while (isCurrent(mine)) {
            pictured = false
            try {
                _state.value = _state.value.copy(status = if (attempt == 0) "Asking the camera for its picture…" else "Reconnecting…", playing = false)
                val url = prepare(misses)
                if (!isCurrent(mine)) break
                _state.value = _state.value.copy(status = "Opening the video…")
                val rtsp = RtspClient(url, bind, log)
                client = rtsp
                rtsp.use { play(it, mine) }
                attempt = 0
            } catch (e: Exception) {
                if (!isCurrent(mine)) break
                attempt++
                val reason = e.message ?: e.javaClass.simpleName
                log("live: $reason")
                _state.value = _state.value.copy(status = reason, playing = false, fps = 0)
                SystemClock.sleep((1500L * attempt).coerceAtMost(6000L))
            } finally {
                if (generation == mine) client = null
                // A session that showed a picture proves the address; one that never did counts
                // toward trying another (see CameraProtocol.prepareLive).
                if (pictured) misses = 0 else if (isCurrent(mine)) misses++
            }
        }
        if (generation == mine || !running) _state.value = _state.value.copy(status = "Stopped", playing = false, fps = 0)
    }

    private fun play(rtsp: RtspClient, mine: Int) {
        val track = rtsp.start()
        log("live: ${track.codec} from ${track.control}, transport \"${rtsp.transport}\", video on channel ${rtsp.videoChannel}, " +
            "SDP parameter sets: sps=${track.sps != null} pps=${track.pps != null} vps=${track.vps != null}")
        _state.value = _state.value.copy(status = "Waiting for the first picture…", codec = track.codec)
        val decoder = Decoder(track.codec)
        // Pictures go to the decoder as they arrive; when they reach the screen is decided on the
        // way out, by their timestamps (see Decoder.drain).
        var lastArrival = 0L
        var lastTimestamp = -1L
        val depacketizer = RtpDepacketizer(track.codec) {
            val now = SystemClock.elapsedRealtime()
            if (lastArrival > 0) {
                val gap = now - lastArrival
                if (gap > maxGap) maxGap = gap
                if (gap > 100) longGaps++
            }
            lastArrival = now
            if (lastTimestamp >= 0 && (it.rtpTimestamp - lastTimestamp).toInt() < 0) reordered++
            lastTimestamp = it.rtpTimestamp
            decoder.feed(it)
        }
        depacketizer.vps = track.vps
        depacketizer.sps = track.sps
        depacketizer.pps = track.pps
        decoder.depacketizer = depacketizer
        // Until the first picture is on screen, say every few seconds what has arrived - the one
        // place a black screen can be told apart from a camera that sends nothing.
        val perChannel = HashMap<Int, Int>()
        var bytes = 0L
        var lastReport = SystemClock.elapsedRealtime()
        try {
            rtsp.pump({ isCurrent(mine) }, { channel, length ->
                perChannel[channel] = (perChannel[channel] ?: 0) + 1
                bytes += length
                val now = SystemClock.elapsedRealtime()
                if (_state.value.playing && now - lastReport > 10_000) {
                    lastReport = now
                    log("live: playing ${_state.value.fps} fps, ${bytes / 1024} KB so far, access units ${decoder.units}, " +
                        "dropped ${depacketizer.droppedUnits}, input buffers refused ${decoder.inputRefused}, " +
                        "arrival: longest gap $maxGap ms, $longGaps gaps over 100 ms, out-of-order (B) pictures $reordered, " +
                        "schedule resets ${decoder.lateRenders}, drift corrections ${decoder.slews}")
                    maxGap = 0
                    longGaps = 0
                    reordered = 0
                    decoder.lateRenders = 0
                    decoder.slews = 0
                } else if (!_state.value.playing && now - lastReport > 5000) {
                    lastReport = now
                    log("live: no picture yet - packets by channel $perChannel, $bytes bytes, access units ${decoder.units}, " +
                        "key frames ${decoder.keyUnits}, parameter sets ready=${depacketizer.ready}, surface=${surface != null}, " +
                        "decoder=${decoder.running}, dropped ${depacketizer.droppedUnits}")
                }
            }) { packet, length -> depacketizer.onRtp(packet, length) }
        } finally {
            decoder.release()
        }
    }

    @Volatile private var maxGap = 0L
    @Volatile private var longGaps = 0

    @Volatile private var reordered = 0

    /** The decoder and everything that decides when it can run. Used only on the live thread. */
    private inner class Decoder(private val codec: VideoCodec) {
        lateinit var depacketizer: RtpDepacketizer
        private var mediaCodec: MediaCodec? = null
        private var configuredFor = -1
        private var waitingForKey = true
        private var firstTimestamp = -1L
        private val info = MediaCodec.BufferInfo()
        private var windowStart = SystemClock.elapsedRealtime()
        private var windowFrames = 0
        var units = 0
        var keyUnits = 0
        var inputRefused = 0
        private var announced = false
        @Volatile var lateRenders = 0
        @Volatile var slews = 0
        private var renderBaseNs = 0L
        private var renderBasePtsUs = 0L
        private var keyWaitSince = -1L
        val running: Boolean get() = mediaCodec != null

        fun feed(unit: AccessUnit) {
            units++
            if (unit.keyFrame) keyUnits++
            val now = SystemClock.elapsedRealtime()
            if (keyWaitSince < 0) keyWaitSince = now
            // The LINGTUO S1 sends no IDR at all - its encoder refreshes the picture gradually -
            // so waiting for a key frame waits forever. Wait a little for one; then start on
            // whatever arrives, and let the first second or so be smeared until the refresh
            // has swept the whole picture.
            val canStart = unit.keyFrame || now - keyWaitSince > KEY_FRAME_PATIENCE_MS
            val target = surface
            if (mediaCodec != null && (configuredFor != surfaceVersion || target == null)) release()
            if (mediaCodec == null) {
                if (target == null || !canStart || !depacketizer.ready) return
                configure(target)
                if (mediaCodec == null) return
                if (!unit.keyFrame) log("live: no key frame in ${KEY_FRAME_PATIENCE_MS} ms; decoding without one")
            }
            if (waitingForKey) {
                if (!canStart) return
                waitingForKey = false
            }
            val codecNow = mediaCodec ?: return
            if (firstTimestamp < 0) firstTimestamp = unit.rtpTimestamp
            // Signed: with B pictures a later one in decode order can carry an earlier timestamp.
            val ptsUs = (unit.rtpTimestamp - firstTimestamp).toInt().toLong() * 1_000_000L / 90_000L
            try {
                val index = codecNow.dequeueInputBuffer(20_000)
                if (index < 0) inputRefused++
                if (index >= 0) {
                    val buffer = codecNow.getInputBuffer(index) ?: return
                    buffer.clear()
                    if (unit.data.size > buffer.capacity()) {
                        codecNow.queueInputBuffer(index, 0, 0, ptsUs, 0)
                        waitingForKey = true
                    } else {
                        buffer.put(unit.data)
                        codecNow.queueInputBuffer(
                            index, 0, unit.data.size, ptsUs,
                            if (unit.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        )
                    }
                }
                drain(codecNow)
            } catch (e: IllegalStateException) {
                log("live: decoder stopped (${e.message}); waiting for the next key frame")
                release()
            }
        }

        private fun configure(target: Surface) {
            try {
                val format = MediaFormat.createVideoFormat(codec.mime, 1920, 1080)
                val config = depacketizer.codecConfig()
                if (codec == VideoCodec.H264) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(depacketizer.sps!!)))
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(depacketizer.pps!!)))
                } else {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
                }
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 shl 20)
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                val created = MediaCodec.createDecoderByType(codec.mime)
                created.configure(format, target, null, 0)
                created.start()
                mediaCodec = created
                configuredFor = surfaceVersion
                waitingForKey = true
                log("live: decoder ${created.name}")
            } catch (e: Exception) {
                log("live: the phone could not start a ${codec.name} decoder: ${e.message}")
                _state.value = _state.value.copy(status = "This phone cannot decode the camera's ${codec.name} video.")
                mediaCodec = null
            }
        }

        private fun drain(c: MediaCodec) {
            while (true) {
                val index = c.dequeueOutputBuffer(info, 0)
                when {
                    index >= 0 -> {
                        // Shown when its timestamp says, not the instant the decoder lets go of it:
                        // decoders release pictures in bunches, and on-release rendering turned every
                        // bunch into a stumble.
                        val nowNs = System.nanoTime()
                        if (renderBaseNs == 0L) {
                            renderBaseNs = nowNs + RENDER_DELAY_NS
                            renderBasePtsUs = info.presentationTimeUs
                        }
                        var at = renderBaseNs + (info.presentationTimeUs - renderBasePtsUs) * 1000
                        when {
                            at < nowNs - 40_000_000L || at > nowNs + 1_000_000_000L -> {
                                // Fallen behind, or the camera's clock jumped: start again from here.
                                lateRenders++
                                renderBaseNs = nowNs + RENDER_DELAY_NS
                                renderBasePtsUs = info.presentationTimeUs
                                at = renderBaseNs
                            }
                            at > nowNs + RENDER_DELAY_NS + DRIFT_ALLOWANCE_NS -> {
                                // A camera clock a little faster than the phone's pushes the schedule
                                // later and later - a delay that keeps growing. Pulled back 2 ms a
                                // picture, never in one jump: a reset was itself a visible stumble.
                                renderBaseNs -= SLEW_NS
                                at -= SLEW_NS
                                slews++
                            }
                        }
                        c.releaseOutputBuffer(index, at)
                        windowFrames++
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowStart >= 1000) {
                            _state.value = _state.value.copy(status = "", playing = true, fps = windowFrames)
                            windowFrames = 0
                            windowStart = now
                        } else if (!_state.value.playing) {
                            _state.value = _state.value.copy(status = "", playing = true)
                        }
                        if (!announced) {
                            announced = true
                            pictured = true
                            onFirstPicture()
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else f.getInteger(MediaFormat.KEY_HEIGHT)
                        _state.value = _state.value.copy(width = w, height = h)
                    }
                    else -> return
                }
            }
        }

        fun release() {
            keyWaitSince = -1L
            renderBaseNs = 0L
            mediaCodec?.let {
                try { it.stop() } catch (_: Exception) { }
                try { it.release() } catch (_: Exception) { }
            }
            mediaCodec = null
            waitingForKey = true
        }
    }

    private companion object {
        const val KEY_FRAME_PATIENCE_MS = 1500L
        /**
         * How far ahead of its presentation time each decoded picture is scheduled. Small: it only
         * has to absorb the decoder handing pictures out in bunches, not the network.
         */
        const val RENDER_DELAY_NS = 100_000_000L

        /** How far past the planned delay a picture may drift before the schedule is pulled back. */
        const val DRIFT_ALLOWANCE_NS = 20_000_000L

        /** How much each picture pulls a drifting schedule back: far below one frame (33 ms). */
        const val SLEW_NS = 2_000_000L
    }

    private fun withStartCode(nal: ByteArray): ByteArray =
        ByteArray(nal.size + 4).also { it[3] = 1; System.arraycopy(nal, 0, it, 4, nal.size) }
}
