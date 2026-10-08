// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import io.motohub.android.aa.AapMessage
import io.motohub.android.aa.AapSsl
import io.motohub.android.aa.AccessoryConnection
import io.motohub.android.module.ModuleDashboardListener
import io.motohub.android.module.ModuleDashboardSession
import io.motohub.android.module.MotoHubModuleHost
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * MOTO-HUB projecting the Ride Dashboard onto a head unit, in the Android Auto phone role.
 *
 * Picks up where [io.motohub.android.aa.AapPhoneHandshake] stops: TLS is up, the unit accepted the
 * certificate and answered service discovery. From here this opens the video, input and sensor
 * channels, negotiates a video configuration, asks for the screen, and once the unit grants it,
 * encodes the dashboard the app draws into the encoder's surface and sends it frame by frame.
 *
 * Threads, and why there are three:
 *  - the caller's, which runs [run] and dispatches every message the head unit sends;
 *  - one writer, through which every outgoing message is encrypted and written in order. TLS
 *    records carry sequence numbers, so encryption order must be write order; and a unit that
 *    writes its ack before reading on (LIVI does) deadlocks against a reader blocked on a write;
 *  - the encoder's, which waits for acks when the unit's window is full rather than dropping a
 *    frame: a dropped frame forces a keyframe twenty times its size, which forces more drops.
 *
 * The protocol facts below (message ids, field numbers, the order) are Android Auto's; the
 * comments say where a unit was seen to depend on one.
 */
internal class AapPhoneSession(
    private val host: MotoHubModuleHost,
    private val connection: AccessoryConnection,
    private val ssl: AapSsl,
    private val discovery: PhoneDiscovery,
    private val inbox: BlockingQueue<AapMessage>,
    private val readerAlive: () -> Boolean,
    private val log: (String) -> Unit
) {
    class Outcome(val success: Boolean, val detail: String)

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aa-phone-writer").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val running = AtomicBoolean(true)
    @Volatile private var endReason: String? = null
    @Volatile private var endedWell = false

    private val video = discovery.video
    private val input = discovery.input
    private val sensors = discovery.sensors

    // Video state.
    private var offeredIndices: List<Int> = emptyList()
    private var chosen: PhoneDiscovery.VideoConfig? = null
    private var encoder: PhoneVideoEncoder? = null
    @Volatile private var dashboard: ModuleDashboardSession? = null
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var projecting = false
    @Volatile private var started = false

    // Flow control: frames sent and not yet acked, against the unit's window.
    private val ackLock = Object()
    private var inFlight = 0
    @Volatile private var maxUnacked = DEFAULT_MAX_UNACKED
    @Volatile private var lastAckAt = 0L

    // Statistics for the periodic line and the outcome.
    @Volatile private var framesSent = 0
    @Volatile private var keyFramesSent = 0
    @Volatile private var acksSeen = 0

    // Touch tracking, on the dispatch thread only.
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var moved = false
    private var multiTouch = false
    private var lastPinch = 0f

    fun run(): Outcome {
        val v = video ?: return Outcome(false, "The head unit offered no video sink.")
        if (v.codec != 0 && v.codec != PhoneDiscovery.CODEC_VIDEO_H264_BP) {
            log("The head unit's video sink wants codec ${v.codec}; sending H.264 Baseline anyway.")
        }
        log("Video sink on channel ${v.channel}, display ${v.displayId}: ${v.configs.joinToString()}")
        input?.let { log("Input on channel ${it.channel}: touchscreen ${it.touchWidth}x${it.touchHeight}, ${it.keycodes.size} keycodes.") }
        sensors?.let { log("Sensors on channel ${it.channel}: types ${it.types.joinToString()}") }

        // Every open goes out at once; the unit answers each on its own channel. Audio and the
        // microphone stay closed for now: neither gates video.
        openChannel(v.channel)
        input?.let { openChannel(it.channel) }
        sensors?.let { openChannel(it.channel) }

        var lastStats = SystemClock.elapsedRealtime()
        var lastPing = 0L
        try {
            while (running.get()) {
                val msg = inbox.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (msg != null) {
                    try {
                        dispatch(msg)
                    } catch (e: Exception) {
                        log("Could not handle ch=${msg.channel} type=${msg.type}: ${e.message}")
                    }
                } else if (!readerAlive()) {
                    end("the head unit closed the connection", well = started)
                }
                val now = SystemClock.elapsedRealtime()
                // Our own keepalive. A unit that never pings (this one sent none in 30 s, then cut
                // the USB link with no goodbye, 2026-10-08) may be waiting for ours instead, as
                // Android Auto sends them.
                if (now - lastPing >= PING_EVERY_MS) {
                    lastPing = now
                    pingsSent++
                    send(CHANNEL_CONTROL, MSG_PING_REQUEST, ProtoWriter().varint(1, SystemClock.elapsedRealtimeNanos() / 1000).toByteArray())
                }
                if (started && now - lastStats >= STATS_EVERY_MS) {
                    lastStats = now
                    log(
                        "Projecting: $framesSent frames ($keyFramesSent key), $acksSeen acks, window $inFlight/$maxUnacked; " +
                            "pings $pingsAnswered/$pingsSent answered, ${pingsReceived} from the unit."
                    )
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            end("interrupted", well = started)
        } finally {
            teardown()
        }
        val detail = buildString {
            append(endReason ?: "ended")
            append(". ")
            chosen?.let { append("Video $it; ") }
            append("$framesSent frames sent ($keyFramesSent key), $acksSeen acks.")
        }
        return Outcome(endedWell, detail)
    }

    // ---- Incoming ----

    private fun dispatch(msg: AapMessage) {
        val body = msg.data.copyOfRange(msg.dataOffset.coerceAtMost(msg.size), msg.size)
        val channel = msg.channel
        // The first message of each kind, so a log shows everything the unit ever said - the one
        // thing a silent disconnect leaves to go on. Acks would drown it and are counted instead.
        if (!(channel == video?.channel && msg.type == MSG_MEDIA_ACK) && seenKinds.add(channel to msg.type)) {
            log("First from the head unit on channel $channel: type ${msg.type}, ${body.size} bytes.")
        }
        when {
            channel == CHANNEL_CONTROL -> onControl(msg.type, body)
            channel == video?.channel -> onVideo(msg.type, body)
            channel == input?.channel -> onInput(msg.type, body)
            channel == sensors?.channel -> onSensor(msg.type, body)
            else -> log("Ignored type ${msg.type} on channel $channel: nothing of ours is open there.")
        }
    }

    private fun onControl(type: Int, body: ByteArray) {
        when (type) {
            MSG_PING_RESPONSE -> pingsAnswered++
            MSG_PING_REQUEST -> {
                pingsReceived++
                // The timestamp is an int64 and goes back unchanged: a unit that checks the echo
                // gives up on a phone whose pings come back cut short.
                val timestamp = ProtoMessage.parse(body).long(1, SystemClock.elapsedRealtimeNanos())
                send(CHANNEL_CONTROL, MSG_PING_RESPONSE, ProtoWriter().varint(1, timestamp).toByteArray())
            }
            MSG_BYEBYE_REQUEST -> {
                val reason = ProtoMessage.parse(body).int(1)
                byeByeSeen = true
                send(CHANNEL_CONTROL, MSG_BYEBYE_RESPONSE, ByteArray(0))
                end("the head unit ended the session (${byeByeReason(reason)})", well = started)
            }
            MSG_BYEBYE_RESPONSE -> byeByeSeen = true
            MSG_CHANNEL_CLOSE_NOTIFICATION -> log("The head unit closed a channel on the control channel.")
            MSG_AUDIO_FOCUS_NOTIFICATION, MSG_NAV_FOCUS_NOTIFICATION, MSG_VOICE_SESSION_NOTIFICATION ->
                log("Control message type $type, nothing to answer.")
            else -> log("Control message type $type (${body.size} bytes) left unanswered.")
        }
    }

    private fun onVideo(type: Int, body: ByteArray) {
        val v = video ?: return
        when (type) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = ProtoMessage.parse(body).int(1)
                if (status != STATUS_SUCCESS) {
                    end("the head unit refused the video channel (status $status)", well = false)
                    return
                }
                log("Video channel open. Asking for an H.264 Baseline sink.")
                send(v.channel, MSG_MEDIA_SETUP, ProtoWriter().varint(1, PhoneDiscovery.CODEC_VIDEO_H264_BP).toByteArray())
            }
            MSG_MEDIA_CONFIG -> onVideoConfig(v, ProtoMessage.parse(body))
            MSG_VIDEO_FOCUS_NOTIFICATION -> {
                val mode = ProtoMessage.parse(body).int(1, VIDEO_FOCUS_PROJECTED)
                onVideoFocus(mode == VIDEO_FOCUS_PROJECTED || mode == VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS)
            }
            // Not the direction the protocol has it travel, but LIVI sends one. Logged, not obeyed.
            MSG_VIDEO_FOCUS_REQUEST -> {
                val request = ProtoMessage.parse(body)
                log("The head unit sent a focus request of its own: mode ${request.int(2)}, reason ${request.int(3)}.")
            }
            MSG_MEDIA_ACK -> onAck(ProtoMessage.parse(body).int(2, 1))
            MSG_CHANNEL_CLOSE_NOTIFICATION -> end("the head unit closed the video channel", well = started)
            else -> log("Video message type $type (${body.size} bytes) left unanswered.")
        }
    }

    /**
     * The unit's answer to Setup: how many frames it lets us have in flight, and which of the
     * configurations from service discovery it will take now. Asks for the screen with the one
     * picked; nothing is encoded until the unit grants it.
     */
    private fun onVideoConfig(v: PhoneDiscovery.VideoService, config: ProtoMessage) {
        val status = config.int(1)
        config.int(2).takeIf { it > 0 }?.let { maxUnacked = it }
        offeredIndices = config.varints(3).map { it.toInt() }
        val pick = pickConfig(v, offeredIndices) ?: run {
            end("the head unit offered no video configuration MOTO-HUB can encode ($offeredIndices)", well = false)
            return
        }
        chosen = pick
        log("Config: status $status, window $maxUnacked, offered $offeredIndices; chose #${pick.index}, $pick.")
        // Mode and reason only. The display's channel is what a protocol-1.3 unit wants in a
        // request to give its screen *back*, and no unit has needed it to hand the screen over.
        send(
            v.channel,
            MSG_VIDEO_FOCUS_REQUEST,
            ProtoWriter().varint(2, VIDEO_FOCUS_PROJECTED).varint(3, VIDEO_FOCUS_REASON).toByteArray()
        )
    }

    /**
     * The offered configuration that best fills the unit's screen: the smallest whose visible
     * width reaches most of the touchscreen's, else the largest there is. Always taking the
     * smallest puts 800x480 on a 1540x720 screen and the unit stretches it. With no touchscreen
     * to go by, the smallest is the safe choice.
     *
     * Nothing above 1080p: the phone is drawing a live map into the same GPU, and a frame it
     * cannot encode in time is a frame the unit's window waits on.
     */
    private fun pickConfig(v: PhoneDiscovery.VideoService, offered: List<Int>): PhoneDiscovery.VideoConfig? {
        fun pixels(c: PhoneDiscovery.VideoConfig): Int = c.size?.let { it.first * it.second } ?: Int.MAX_VALUE
        val candidates = v.configs.filter {
            (offered.isEmpty() || it.index in offered) && pixels(it) <= MAX_ENCODE_PIXELS
        }
        if (candidates.isEmpty()) return null
        val touchWidth = input?.touchWidth ?: 0
        if (touchWidth <= 0) return candidates.minByOrNull(::pixels)
        val wide = candidates.filter { c ->
            val width = c.size?.first ?: 0
            width - c.marginWidth >= touchWidth * 8 / 10
        }
        return wide.minByOrNull(::pixels) ?: candidates.maxByOrNull(::pixels)
    }

    /**
     * Who has the unit's screen. The first grant starts the encoder and the dashboard; after that
     * the unit may take its screen back (its own buttons, a reversing camera) and return it later.
     * While it has the screen nothing is sent, and on return the stream restarts from the codec
     * configuration and a fresh keyframe, since its decoder may have been reset in between.
     */
    private fun onVideoFocus(projected: Boolean) {
        val v = video ?: return
        if (!started) {
            // A unit repeats the notification while it waits for frames; only the first counts.
            if (!projected) {
                log("The head unit keeps its own screen for now.")
                return
            }
            started = true
            startProjection()
            return
        }
        if (projected == projecting) return
        if (projected) {
            log("The head unit handed its screen back: restarting the stream.")
            // Frames sent just before it took over may never be acked.
            synchronized(ackLock) {
                inFlight = 0
                ackLock.notifyAll()
            }
            chosen?.let { sendStart(v, it) }
            codecConfig?.let { send(v.channel, MSG_MEDIA_CODEC_CONFIG, it) }
            projecting = true
            encoder?.requestSyncFrame()
        } else {
            // A unit that switches on the focus answer alone needs nothing more; one that keeps
            // showing the last frame (a 2018 Uconnect did) needs the Stop.
            log("The head unit took its screen back: stream stopped, session kept.")
            projecting = false
            synchronized(ackLock) { ackLock.notifyAll() }
            send(v.channel, MSG_MEDIA_STOP, ProtoWriter().varint(1, SESSION_ID).toByteArray())
        }
    }

    private fun startProjection() {
        val v = video ?: return
        val config = chosen ?: v.configs.firstOrNull()?.also { chosen = it } ?: run {
            end("the head unit granted its screen with no video configuration agreed", well = false)
            return
        }
        val (width, height) = config.size ?: run {
            end("the head unit's resolution #${config.resolution} has no known size", well = false)
            return
        }
        val density = config.density.takeIf { it > 0 } ?: DEFAULT_DENSITY_DPI
        touchSlop = TOUCH_SLOP_DP * density / 160f
        touchMapping = chooseTouchMapping(input?.touchWidth ?: 0, input?.touchHeight ?: 0, width, height, config)
        log("Touch: $touchMapping.")

        sendStart(v, config)
        val enc = PhoneVideoEncoder(width, height, config.fps, log, ::onCodecConfig, ::onFrame)
        val surface = try {
            enc.start()
        } catch (e: Exception) {
            enc.stop()
            end("the encoder would not start: ${e.message}", well = false)
            return
        }
        encoder = enc
        projecting = true

        attachDashboard(surface, width, height, density, config)
        log("Projecting at $config, ${density}dpi; the app is starting the dashboard.")
    }

    /** Start for our one session, at the configuration agreed. The window restarts with it. */
    private fun sendStart(v: PhoneDiscovery.VideoService, config: PhoneDiscovery.VideoConfig) {
        send(v.channel, MSG_MEDIA_START, ProtoWriter().varint(1, SESSION_ID).varint(2, config.index).toByteArray())
        lastAckAt = SystemClock.elapsedRealtime()
    }

    /**
     * Starts the app's dashboard on the encoder's surface, on a thread of its own, and without
     * waiting for it.
     *
     * Not the main thread: the app's scenes start by posting their work to the main thread and
     * waiting for it, so called from there they wait out their whole timeout and fail - eight
     * seconds of frozen UI and no 3D map, measured on 2026-10-08 - and the work they queued then
     * runs anyway and takes the encoder's surface from the Canvas fallback. Not this dispatch
     * thread either: one stuck behind the app answers no ping, and the head unit drops us for the
     * app's slowness. The encoder keeps its surface until [attachDone] says the app has finished
     * with this call, so a session ending meanwhile never pulls the surface out from under it.
     */
    private fun attachDashboard(
        surface: Surface,
        width: Int,
        height: Int,
        density: Int,
        config: PhoneDiscovery.VideoConfig
    ) {
        val done = CountDownLatch(1)
        attachDone = done
        val listener = object : ModuleDashboardListener {
            override fun onStopped(reason: String) {
                // A refusal arrives here inside attach(), before [dashboard] is set: that is not a
                // session that ran. A stop after it is the app ending one that did.
                end("the app stopped the dashboard: $reason", well = dashboard != null)
            }
        }
        Thread({
            try {
                val attached = try {
                    host.dashboard.attach(surface, width, height, density, false, listener)
                } catch (e: Exception) {
                    end("the app could not draw a dashboard: ${e.message ?: e.javaClass.simpleName}", well = false)
                    null
                }
                when {
                    attached == null -> end("the app could not draw a dashboard", well = false)
                    // Ended while the app was starting it: nobody else will stop it now.
                    !running.get() -> attached.detach()
                    else -> {
                        dashboard = attached
                        main.post { applyScreen(attached, width, height, config) }
                        log("The Ride Dashboard is drawing.")
                    }
                }
            } finally {
                done.countDown()
            }
        }, "aa-phone-attach").apply { isDaemon = true; start() }
    }

    /** On the main thread, once: the margins the unit crops and the night it last reported. */
    private fun applyScreen(d: ModuleDashboardSession, width: Int, height: Int, config: PhoneDiscovery.VideoConfig) {
        // The unit crops its margins off the frame, evenly on both sides: keep the dashboard
        // inside what is left.
        if (config.marginWidth > 0 || config.marginHeight > 0) {
            val left = config.marginWidth / 2
            val top = config.marginHeight / 2
            val right = width - (config.marginWidth - left)
            val bottom = height - (config.marginHeight - top)
            d.setVisibleArea(left, top, right, bottom)
            d.setStableArea(left, top, right, bottom)
        }
        night?.let { d.setNightMode(it) }
    }

    /** Every dashboard call goes to the main thread; the contract makes a late one harmless. */
    private inline fun onDashboard(crossinline block: ModuleDashboardSession.() -> Unit) {
        val d = dashboard ?: return
        main.post { d.block() }
    }

    // ---- Video out (the encoder's thread) ----

    private fun onCodecConfig(config: ByteArray) {
        codecConfig = config
        val v = video ?: return
        if (!projecting) return // resent with the next Start
        send(v.channel, MSG_MEDIA_CODEC_CONFIG, config)
        log("Sent the codec configuration (${config.size} bytes).")
    }

    private fun onFrame(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        val v = video ?: return
        if (!awaitWindow()) return
        // Media data: an 8-byte big-endian timestamp in microseconds, then the access unit.
        val payload = ByteArray(TIMESTAMP_SIZE + data.size)
        for (i in 0 until TIMESTAMP_SIZE) {
            payload[i] = (presentationTimeUs ushr ((TIMESTAMP_SIZE - 1 - i) * 8)).toByte()
        }
        System.arraycopy(data, 0, payload, TIMESTAMP_SIZE, data.size)
        // Counted before it is queued: the writer may send it and the ack come back before this
        // thread runs again, and an ack counted ahead of its frame would leave the window full.
        synchronized(ackLock) { inFlight++ }
        send(v.channel, MSG_MEDIA_DATA, payload)
        framesSent++
        if (keyFrame) keyFramesSent++
        if (framesSent == 1) log("First frame sent (${data.size} bytes, key=$keyFrame).")
    }

    /**
     * Blocks the encoder until the unit's window has room. A lost ack would otherwise hold the
     * stream forever, which looks like a frozen picture: after [ACK_STALL_MS] of silence the
     * window is taken as cleared.
     *
     * False when the frame should not go out at all: the session is ending, or the unit has its
     * own screen up and would throw the frame away.
     */
    private fun awaitWindow(): Boolean {
        synchronized(ackLock) {
            while (running.get() && projecting && inFlight >= maxUnacked) {
                val since = SystemClock.elapsedRealtime() - lastAckAt
                if (since >= ACK_STALL_MS) {
                    log("No ack for ${since}ms with $inFlight in flight: taking the window as cleared.")
                    inFlight = 0
                    lastAckAt = SystemClock.elapsedRealtime() // one warning per stall, not per frame
                    break
                }
                ackLock.wait(ACK_STALL_MS - since)
            }
            return running.get() && projecting
        }
    }

    private fun onAck(count: Int) {
        synchronized(ackLock) {
            inFlight = (inFlight - count.coerceAtLeast(1)).coerceAtLeast(0)
            acksSeen++
            lastAckAt = SystemClock.elapsedRealtime()
            ackLock.notifyAll()
        }
    }

    // ---- Input ----

    private fun onInput(type: Int, body: ByteArray) {
        val inp = input ?: return
        when (type) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = ProtoMessage.parse(body).int(1)
                if (status != STATUS_SUCCESS) {
                    // Input is a nicety: the dashboard rides fine without a touchscreen.
                    log("The head unit refused the input channel (status $status); projecting without touch.")
                    return
                }
                // Many units send nothing from their buttons until the phone names the keys it
                // wants. Ask for what the unit offered; a unit that offered none gets ours, since
                // some leave the list empty and send keys anyway.
                val keycodes = inp.keycodes.takeIf { it.isNotEmpty() } ?: DEFAULT_KEYCODES
                send(inp.channel, MSG_INPUT_BINDING_REQUEST, ProtoWriter().packedVarints(1, keycodes).toByteArray())
            }
            MSG_INPUT_BINDING_RESPONSE -> log("Key binding answered: status ${ProtoMessage.parse(body).int(1)}.")
            MSG_INPUT_EVENT -> {
                if (dashboard == null) return
                val report = ProtoMessage.parse(body)
                report.message(INPUT_TOUCH)?.let(::onTouch)
                report.message(INPUT_KEY)?.let(::onKeys)
                report.message(INPUT_RELATIVE)?.let(::onRelative)
            }
            MSG_CHANNEL_CLOSE_NOTIFICATION -> log("The head unit closed the input channel.")
            else -> log("Input message type $type (${body.size} bytes) left unanswered.")
        }
    }

    /**
     * A touch, brought into the coordinates of the video we send. Units disagree on what they
     * report in, and the touchscreen size they declare is the only hint - see [chooseTouchMapping]
     * for how it is read, and below for how a wrong reading corrects itself. Then a clamp to the
     * frame.
     *
     * Turned into the dashboard's three gestures: a short touch that did not travel is a tap, one
     * finger travelling is a drag, two are a pinch. Once a second finger has been down the
     * gesture stays a pinch to the end, so lifting one finger does not jump the map.
     */
    private fun onTouch(touch: ProtoMessage) {
        val config = chosen ?: return
        val (width, height) = config.size ?: return
        val raws = touch.messages(1)
        // A touch outside the declared touchscreen disproves reading it as one: the unit maps to
        // our video itself. Corrected once, from that touch on, and said so.
        if (touchMapping.scaled && raws.any { it.int(1) > touchMapping.sourceWidth + TOUCH_BOUNDS_SLACK ||
                it.int(2) > touchMapping.sourceHeight + TOUCH_BOUNDS_SLACK }
        ) {
            touchMapping = TouchMapping.VIDEO
            log("A touch fell outside the declared touchscreen: reading touches as video coordinates from now on.")
        }
        val points = raws.map { p ->
            touchMapping.x(p.int(1)).coerceIn(0, width - 1).toFloat() to
                touchMapping.y(p.int(2)).coerceIn(0, height - 1).toFloat()
        }
        if (points.isEmpty()) return
        val (x, y) = points[touch.int(2).coerceIn(0, points.size - 1)]
        val action = touch.int(3)
        // Once per kind: whether a unit reports a second finger at all is the first question
        // when a pinch does nothing (many single-touch panels never do).
        if (seenTouches.add(action to points.size.coerceAtMost(3))) {
            val raw = touch.messages(1).firstOrNull()
            log(
                "Touch from the head unit: action $action with ${points.size} finger(s), " +
                    "raw ${raw?.int(1)},${raw?.int(2)} -> ${x.toInt()},${y.toInt()}."
            )
        }
        when (action) {
            TOUCH_ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y
                downAt = SystemClock.elapsedRealtime()
                moved = false
                multiTouch = false
                lastPinch = 0f
            }
            TOUCH_ACTION_POINTER_DOWN -> {
                multiTouch = true
                lastPinch = spread(points)
            }
            TOUCH_ACTION_MOVE -> when {
                points.size >= 2 -> {
                    val spread = spread(points)
                    if (lastPinch > 0f && spread > 0f) {
                        val factor = spread / lastPinch
                        if (pinchesLogged < PINCHES_LOGGED) {
                            pinchesLogged++
                            log("Pinch to the dashboard: factor %.3f.".format(factor))
                        }
                        val focusX = (points[0].first + points[1].first) / 2f
                        val focusY = (points[0].second + points[1].second) / 2f
                        onDashboard { scale(focusX, focusY, factor) }
                    }
                    lastPinch = spread
                }
                !multiTouch -> {
                    val (px, py) = points[0]
                    if (!moved && hypot(px - downX, py - downY) >= touchSlop) moved = true
                    if (moved) {
                        // The distance travelled since the last call, as GestureDetector and the
                        // Car App Library both measure it: previous minus current.
                        val dx = lastX - px
                        val dy = lastY - py
                        onDashboard { scroll(dx, dy) }
                    }
                    lastX = px
                    lastY = py
                }
            }
            TOUCH_ACTION_POINTER_UP -> lastPinch = 0f
            TOUCH_ACTION_UP -> {
                if (!moved && !multiTouch && SystemClock.elapsedRealtime() - downAt <= TAP_MAX_MS) {
                    onDashboard { tap(x, y) }
                }
                multiTouch = false
                lastPinch = 0f
            }
            TOUCH_ACTION_CANCEL -> {
                moved = true // nothing that was cancelled may end as a tap
                multiTouch = false
                lastPinch = 0f
            }
        }
    }

    private fun spread(points: List<Pair<Float, Float>>): Float =
        if (points.size < 2) 0f
        else hypot(points[0].first - points[1].first, points[0].second - points[1].second)

    /**
     * The unit's own buttons, as android.view.KeyEvent codes, onto the three verbs the handlebar
     * already has: sideways cycles the panels, the centre toggles the full-screen map, up and down
     * toggle the map zoom. Acted on when pressed, so a held key does not fire twice.
     */
    private fun onKeys(keyEvent: ProtoMessage) {
        for (key in keyEvent.messages(1)) {
            if (key.int(2) == 0) continue // released
            when (val keycode = key.int(1)) {
                KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> onDashboard { cyclePanels() }
                KEYCODE_DPAD_CENTER, KEYCODE_ENTER -> onDashboard { toggleFullscreenMap() }
                KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN -> onDashboard { toggleMapZoom() }
                else -> log("Key $keycode from the head unit has no dashboard action.")
            }
        }
    }

    /** A rotary knob: one panel per turn event, whichever way, as the handlebar's own cycle goes one way. */
    private fun onRelative(event: ProtoMessage) {
        for (rel in event.messages(1)) {
            // int32 on the wire, so a turn the other way arrives as a ten-byte negative varint.
            if (rel.int(1) == KEYCODE_ROTARY && rel.int(2) != 0) onDashboard { cyclePanels() }
        }
    }

    // ---- Sensors ----

    private fun onSensor(type: Int, body: ByteArray) {
        val s = sensors ?: return
        when (type) {
            MSG_CHANNEL_OPEN_RESPONSE -> {
                val status = ProtoMessage.parse(body).int(1)
                if (status != STATUS_SUCCESS) {
                    log("The head unit refused the sensor channel (status $status).")
                    return
                }
                // Only the unit's day/night signal, its headlights or light sensor: the one
                // reading the dashboard has a use for. Period 0 asks for every change.
                if (SENSOR_NIGHT in s.types) {
                    send(s.channel, MSG_SENSOR_START_REQUEST, ProtoWriter().varint(1, SENSOR_NIGHT).varint(2, 0).toByteArray())
                } else {
                    log("The head unit offers no night sensor.")
                }
            }
            MSG_SENSOR_START_RESPONSE -> log("Night sensor started: status ${ProtoMessage.parse(body).int(1)}.")
            MSG_SENSOR_EVENT -> {
                // SensorBatch carries each sensor's readings under the sensor's own number.
                val reading = ProtoMessage.parse(body).messages(SENSOR_NIGHT).lastOrNull() ?: return
                val isNight = reading.int(1) != 0
                if (isNight == night) return
                night = isNight
                log("The head unit says it is ${if (isNight) "night" else "day"}.")
                onDashboard { setNightMode(isNight) }
            }
            MSG_CHANNEL_CLOSE_NOTIFICATION -> log("The head unit closed the sensor channel.")
            else -> log("Sensor message type $type (${body.size} bytes) left unanswered.")
        }
    }

    /** The unit's day/night, written on the dispatch thread; null until it says. */
    @Volatile private var night: Boolean? = null

    /** Counted down once the app has returned from attach; null until projection starts. */
    @Volatile private var attachDone: CountDownLatch? = null

    /** Touch kinds already logged once, and pinches logged so far; dispatch thread only. */
    private val seenTouches = HashSet<Pair<Int, Int>>()
    private var pinchesLogged = 0

    /** How the unit's touch coordinates become ours; see [chooseTouchMapping]. Dispatch thread only. */
    private var touchMapping = TouchMapping.VIDEO

    /** A touch that travels this far is a drag, not a tap. Set from the unit's density. */
    private var touchSlop = TOUCH_SLOP_DP

    // ---- Outgoing ----

    /**
     * Channel open: priority 0, and the service's id, which is also its channel. Sent with the
     * control bit although it travels on the service's channel: that is how the protocol marks
     * it, and a unit that gets it marked as a service message drops the USB link outright.
     */
    private fun openChannel(channel: Int) {
        send(channel, MSG_CHANNEL_OPEN_REQUEST, ProtoWriter().varint(1, 0).varint(2, channel).toByteArray(), control = true)
    }

    /** Queues one message for the writer thread; never blocks the caller on the wire. */
    private fun send(channel: Int, type: Int, body: ByteArray, control: Boolean = false) {
        val plain = ByteArray(MSG_TYPE_SIZE + body.size)
        plain[0] = (type shr 8).toByte()
        plain[1] = type.toByte()
        System.arraycopy(body, 0, plain, MSG_TYPE_SIZE, body.size)
        try {
            writer.execute { write(channel, plain, control) }
        } catch (e: RejectedExecutionException) {
            // The writer is gone: the session is over and nothing more goes out.
        }
    }

    /**
     * Encrypts and writes one message, split into frames as the protocol does: the plaintext is
     * cut at 16 KB (one TLS record each), every piece is encrypted on its own, and the first
     * carries the whole plaintext's length. Keyframes for anything busier than a test card pass
     * 16 KB routinely.
     */
    private fun write(channel: Int, plain: ByteArray, control: Boolean) {
        if (writeFailed) return
        var offset = 0
        while (offset < plain.size) {
            val size = minOf(MAX_FRAME_PAYLOAD, plain.size - offset)
            val first = offset == 0
            val last = offset + size >= plain.size
            val frameType = when {
                first && last -> FRAME_BULK
                first -> FRAME_FIRST
                last -> FRAME_LAST
                else -> FRAME_MIDDLE
            }
            val headerSize = if (frameType == FRAME_FIRST) FIRST_HEADER_SIZE else AapMessage.HEADER_SIZE
            val chunk = ByteArray(headerSize + size)
            System.arraycopy(plain, offset, chunk, headerSize, size)
            // encrypt() leaves the bytes before the offset free, so the header goes in place.
            val sealed = ssl.encrypt(headerSize, size, chunk)
            if (sealed == null) {
                fail("TLS refused to encrypt a ${plain.size}-byte message")
                return
            }
            val out = sealed.data
            val sealedSize = sealed.limit - headerSize
            out[0] = channel.toByte()
            out[1] = (frameType or FLAG_ENCRYPTED or (if (control) FLAG_CONTROL else 0)).toByte()
            out[2] = (sealedSize shr 8).toByte()
            out[3] = sealedSize.toByte()
            if (frameType == FRAME_FIRST) {
                out[4] = (plain.size ushr 24).toByte()
                out[5] = (plain.size ushr 16).toByte()
                out[6] = (plain.size ushr 8).toByte()
                out[7] = plain.size.toByte()
            }
            if (connection.sendBlocking(out, sealed.limit, WRITE_TIMEOUT_MS) < 0) {
                fail("the link to the head unit closed (cable pulled, or the session stopped)")
                return
            }
            offset += size
        }
    }

    /** On the writer thread: nothing after a failed write can arrive whole, so nothing more goes. */
    private fun fail(reason: String) {
        writeFailed = true
        end(reason, well = started)
    }

    @Volatile private var writeFailed = false

    /** Kinds of message already logged once, on the dispatch thread only. */
    private val seenKinds = HashSet<Pair<Int, Int>>()
    private var pingsSent = 0
    @Volatile private var pingsAnswered = 0
    @Volatile private var pingsReceived = 0
    @Volatile private var byeByeSeen = false

    // ---- Ending ----

    /** The first reason wins; everything after it is a consequence. */
    @Synchronized
    private fun end(reason: String, well: Boolean) {
        if (!running.get()) return
        endReason = reason
        endedWell = well
        running.set(false)
        log("Ending the session: $reason.")
        synchronized(ackLock) { ackLock.notifyAll() }
    }

    /**
     * Dashboard first, then the encoder: the app stops drawing before the surface it draws into
     * is released. Then a goodbye, unless the unit said it first or is already gone - a unit left
     * waiting on a phone that vanished keeps its screen black until its own timeout. The
     * connection and the TLS context stay the caller's.
     */
    private fun teardown() {
        projecting = false
        synchronized(ackLock) { ackLock.notifyAll() }

        // An attach still running on the main thread draws into the encoder's surface: let it
        // finish (it detaches itself, seeing the session over) before the surface goes.
        attachDone?.let { pending ->
            try {
                if (!pending.await(ATTACH_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    log("The app was still starting the dashboard after ${ATTACH_WAIT_MS / 1000}s; releasing anyway.")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        dashboard?.let { d ->
            dashboard = null
            val detached = CountDownLatch(1)
            main.post {
                try { d.detach() } catch (_: Exception) {} finally { detached.countDown() }
            }
            try {
                detached.await(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        encoder?.stop()
        encoder = null

        if (!byeByeSeen && !writeFailed && readerAlive()) {
            send(CHANNEL_CONTROL, MSG_BYEBYE_REQUEST, ProtoWriter().varint(1, BYEBYE_USER_SELECTION).toByteArray())
        }
        writer.shutdown()
        try {
            if (!writer.awaitTermination(WRITER_DRAIN_MS, TimeUnit.MILLISECONDS)) writer.shutdownNow()
        } catch (e: InterruptedException) {
            writer.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun byeByeReason(reason: Int): String = when (reason) {
        1 -> "the rider chose to"
        2 -> "it switched to another device"
        3 -> "not supported"
        4 -> "not supported right now"
        5 -> "probe supported"
        else -> "reason $reason"
    }

    /**
     * Touch coordinates in a space of [sourceWidth]x[sourceHeight] laid onto the part of our video
     * from ([left], [top]) of [width]x[height]; or, not [scaled], already in video coordinates.
     */
    internal class TouchMapping(
        val scaled: Boolean,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    ) {
        fun x(raw: Int): Int = if (scaled) left + raw * width / sourceWidth else raw
        fun y(raw: Int): Int = if (scaled) top + raw * height / sourceHeight else raw

        override fun toString(): String =
            if (scaled) "touchscreen ${sourceWidth}x$sourceHeight laid onto the visible ${width}x$height at $left,$top"
            else "reported in video coordinates"

        companion object {
            val VIDEO = TouchMapping(false, 0, 0, 0, 0, 0, 0)
        }
    }

    companion object {
        /**
         * How to read a unit's touches, from the touchscreen size it declared and the video agreed.
         *
         * - The size of the video, or nothing declared: video coordinates.
         * - Smaller than the video: the panel's own coordinates, laid onto the part of the frame the
         *   unit actually shows (the video minus the margins it crops, centred). Vincenzo's unit
         *   declares 1024x600 for 1280x720 cropped by 256x120 and reports exactly that way; taken
         *   as video coordinates, every tap landed 128 px left and 60 px up (2026-10-08).
         * - Larger than the video: the unit has mapped its panel onto our video itself. A 2018
         *   Uconnect declares 1258x708 with 800x480 projected and never reports an x past 791.
         *
         * A guess either way, so [onTouch] drops the scaling the first time a touch lands outside
         * the declared panel.
         */
        internal fun chooseTouchMapping(
            touchWidth: Int,
            touchHeight: Int,
            videoWidth: Int,
            videoHeight: Int,
            config: PhoneDiscovery.VideoConfig
        ): TouchMapping {
            if (touchWidth <= 0 || touchHeight <= 0) return TouchMapping.VIDEO
            if (touchWidth >= videoWidth || touchHeight >= videoHeight) return TouchMapping.VIDEO
            val visibleWidth = (videoWidth - config.marginWidth).coerceAtLeast(1)
            val visibleHeight = (videoHeight - config.marginHeight).coerceAtLeast(1)
            return TouchMapping(
                scaled = true,
                sourceWidth = touchWidth,
                sourceHeight = touchHeight,
                left = config.marginWidth / 2,
                top = config.marginHeight / 2,
                width = visibleWidth,
                height = visibleHeight
            )
        }

        private const val CHANNEL_CONTROL = 0

        private const val POLL_MS = 250L
        private const val STATS_EVERY_MS = 10_000L
        private const val PING_EVERY_MS = 3_000L
        private const val ATTACH_WAIT_MS = 20_000L
        private const val DETACH_TIMEOUT_MS = 2_000L
        private const val WRITER_DRAIN_MS = 1_000L
        private const val WRITE_TIMEOUT_MS = 2_000

        /** Until the unit's Config says otherwise. LIVI allows one, so one is never wrong. */
        private const val DEFAULT_MAX_UNACKED = 1
        private const val ACK_STALL_MS = 2_000L

        // Framing: byte 1 is frame type | encryption | message type.
        private const val FRAME_MIDDLE = 0
        private const val FRAME_FIRST = 1
        private const val FRAME_LAST = 2
        private const val FRAME_BULK = 3
        private const val FLAG_CONTROL = 1 shl 2
        private const val FLAG_ENCRYPTED = 1 shl 3
        /** The first frame of a split message carries the total length after the usual four bytes. */
        private const val FIRST_HEADER_SIZE = 8
        /** One TLS record's worth of plaintext, where Android Auto itself splits. */
        private const val MAX_FRAME_PAYLOAD = 0x4000
        private const val MSG_TYPE_SIZE = 2
        private const val TIMESTAMP_SIZE = 8

        // Control channel.
        private const val MSG_CHANNEL_OPEN_REQUEST = 7
        private const val MSG_CHANNEL_OPEN_RESPONSE = 8
        private const val MSG_CHANNEL_CLOSE_NOTIFICATION = 9
        private const val MSG_PING_REQUEST = 11
        private const val MSG_PING_RESPONSE = 12
        private const val MSG_NAV_FOCUS_NOTIFICATION = 14
        private const val MSG_BYEBYE_REQUEST = 15
        private const val MSG_BYEBYE_RESPONSE = 16
        private const val MSG_VOICE_SESSION_NOTIFICATION = 17
        private const val MSG_AUDIO_FOCUS_NOTIFICATION = 19
        private const val STATUS_SUCCESS = 0
        private const val BYEBYE_USER_SELECTION = 1

        // Media channel.
        private const val MSG_MEDIA_DATA = 0
        private const val MSG_MEDIA_CODEC_CONFIG = 1
        private const val MSG_MEDIA_SETUP = 32768
        private const val MSG_MEDIA_START = 32769
        private const val MSG_MEDIA_STOP = 32770
        private const val MSG_MEDIA_CONFIG = 32771
        private const val MSG_MEDIA_ACK = 32772
        private const val MSG_VIDEO_FOCUS_REQUEST = 32775
        private const val MSG_VIDEO_FOCUS_NOTIFICATION = 32776
        private const val SESSION_ID = 1
        private const val VIDEO_FOCUS_PROJECTED = 1
        private const val VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS = 4
        /** The reason a working phone-role implementation sends with its request; units do not appear to read it. */
        private const val VIDEO_FOCUS_REASON = 1
        private const val MAX_ENCODE_PIXELS = 1920 * 1080
        /** A car screen's density when the unit leaves it out. */
        private const val DEFAULT_DENSITY_DPI = 160

        // Input channel.
        private const val MSG_INPUT_EVENT = 32769
        private const val MSG_INPUT_BINDING_REQUEST = 32770
        private const val MSG_INPUT_BINDING_RESPONSE = 32771
        private const val INPUT_TOUCH = 3
        private const val INPUT_KEY = 4
        private const val INPUT_RELATIVE = 6
        private const val TOUCH_ACTION_DOWN = 0
        private const val TOUCH_ACTION_UP = 1
        private const val TOUCH_ACTION_MOVE = 2
        private const val TOUCH_ACTION_CANCEL = 3
        private const val TOUCH_ACTION_POINTER_DOWN = 5
        private const val TOUCH_ACTION_POINTER_UP = 6
        private const val TOUCH_SLOP_DP = 8f
        private const val TAP_MAX_MS = 500L
        private const val PINCHES_LOGGED = 5
        /** Panels report a pixel or two past their declared edge; that alone proves nothing. */
        private const val TOUCH_BOUNDS_SLACK = 4

        private const val KEYCODE_DPAD_UP = 19
        private const val KEYCODE_DPAD_DOWN = 20
        private const val KEYCODE_DPAD_LEFT = 21
        private const val KEYCODE_DPAD_RIGHT = 22
        private const val KEYCODE_DPAD_CENTER = 23
        private const val KEYCODE_ENTER = 66
        private const val KEYCODE_ROTARY = 65536
        private val DEFAULT_KEYCODES = intArrayOf(
            KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN, KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT,
            KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_ROTARY
        )

        // Sensor channel.
        private const val MSG_SENSOR_START_REQUEST = 32769
        private const val MSG_SENSOR_START_RESPONSE = 32770
        private const val MSG_SENSOR_EVENT = 32771
        private const val SENSOR_NIGHT = 10
    }
}
