// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The controller behind every screen. Every call into Python runs on one thread, the one that
 * started it ("paddock-py"); Python never calls back, so state comes from [poll] once a second.
 */
internal class PaddockEngine(private val host: MotoHubModuleHost) : PaddockController {

    private val storage: File = host.storage
    private val py = Executors.newSingleThreadScheduledExecutor { Thread(it, "paddock-py").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var engine: PyObject? = null
    private var lastPositionAt = 0L
    /** The rider ID was forgotten: nothing may start again until MOTO-HUB restarts. */
    @Volatile private var needsRestart = false

    /** The app's foreground service with location, held while a room is open (contract 34). */
    private var keepAlive: AutoCloseable? = null
    /**
     * The keep-alive was opened while the app was in the background (a ride restored after an app
     * update or a restart): Android then denies its location access, and our GPS gets nothing.
     * Opened again as soon as the app is in front. Written on the main thread, read on Python's.
     */
    @Volatile private var keepAliveFromBackground = false

    // The app's livePosition() only has a fix while one of its own screens asks the GPS, so the
    // room asks for its own updates while it is open; the foreground service keeps them coming.
    private val locations = host.context.getSystemService(LocationManager::class.java)
    @Volatile private var latestFix: Location? = null
    private val locationListener = LocationListener { latestFix = it }
    private var tracking = false
    internal val notifier = Notifier(host.context)
    /** The push token last given to Python (contract 51); it changes now and then. */
    private var sharedToken: String? = null
    private val voice: VoiceCall = VoiceCall(host.context, { engine }, object : VoiceCall.Actions {
        override fun answer() { answerCall() }
        override fun decline() { declineCall() }
        override fun hangUp() { this@PaddockEngine.hangUp() }
    }, { line: String -> log(line) }).apply { setRoomNames { c -> _state.value.room(c.roomId)?.name ?: "" } }
    override val callAudio: StateFlow<CallAudio> get() = voice.audioState
    /** The app was in front at the last poll: coming back to it reads the mailbox at once. */
    private var wasInFront = true
    /** Python's relays as last set; null until the first poll decides. Python thread only. */
    private var relaysOn: Boolean? = null
    /** elapsedRealtime when MOTO-HUB was last seen on screen. Python thread only. */
    private var lastInFrontAt = 0L
    private val _alwaysOn = MutableStateFlow(File(storage, ALWAYS_ON).isFile)
    override val alwaysOn: StateFlow<Boolean> = _alwaysOn

    override fun setAlwaysOn(on: Boolean) {
        val f = File(storage, ALWAYS_ON)
        if (on) f.writeText("1") else f.delete()
        _alwaysOn.value = on
        log("always on: $on")
    }

    private val _disconnected = MutableStateFlow(File(storage, DISCONNECTED).isFile)
    override val disconnected: StateFlow<Boolean> = _disconnected

    override fun disconnect() {
        File(storage, DISCONNECTED).writeText("1")
        _disconnected.value = true
        if (_state.value.riding != null) stopRide()
        if (_state.value.call != null) hangUp()
        log("disconnected by the rider")
    }

    override fun connect() {
        File(storage, DISCONNECTED).delete()
        _disconnected.value = false
        log("connected by the rider")
        ensureStarted()
    }

    private val _state = MutableStateFlow(PaddockState())
    override val state: StateFlow<PaddockState> = _state
    private val _profile = MutableStateFlow(readProfile())
    override val profile: StateFlow<Profile?> = _profile
    private val _events = MutableSharedFlow<PaddockEvent>(extraBufferCapacity = 16)
    private val _chat = MutableStateFlow(ChatView())
    override val chat: StateFlow<ChatView> = _chat
    /** The room whose chat is on screen; its messages are read again every poll. */
    @Volatile private var chatRoom: String? = null
    @Volatile private var chatLimit = CHAT_PAGE
    override val events: SharedFlow<PaddockEvent> = _events

    override val defaultRelay = "$DEFAULT_HOST:$DEFAULT_PORT"

    override fun saveProfile(profile: Profile) {
        val clean = profile.copy(name = profile.name.trim().take(32), bike = profile.bike.trim().take(48))
        File(storage, PROFILE).writeText(
            JSONObject().put("name", clean.name).put("color", clean.color).put("bike", clean.bike).toString()
        )
        _profile.value = clean
        if (_state.value.stage == EngineStage.RUNNING) {
            call { it.callAttr("set_profile", clean.name, clean.color, clean.bike) }
        } else {
            ensureStarted()
        }
    }

    override fun ensureStarted() {
        // Disconnected: nothing starts until the rider taps Connect.
        if (_disconnected.value) return
        val p = _profile.value ?: return
        val stage = _state.value.stage
        if (stage == EngineStage.RUNNING || stage == EngineStage.STARTING || needsRestart) return
        _state.value = PaddockState(stage = EngineStage.STARTING, startStep = "Unpacking Paddock (first time only)")
        py.execute {
            try {
                val home = ModulePython.unpack(storage, host.context.noBackupFilesDir, ::log)
                step("Starting the secure engine")
                ModulePython.loadNativeLibraries(home, ::log)
                ModulePython.start(home, storage)
                val module = Python.getInstance().getModule("paddock_engine")
                step("Connecting to the relay")
                val (host, port) = relay()
                val now = _profile.value ?: p
                module.callAttr("start", storage.absolutePath, host, port, now.name, now.color, now.bike)
                engine = module
                // Edited while Python was starting: the room must see the latest profile.
                _profile.value?.takeIf { it != now }?.let { module.callAttr("set_profile", it.name, it.color, it.bike) }
                log("engine running")
                py.scheduleWithFixedDelay(::tick, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                log("start failed: ${t.javaClass.name}")
                // A native library binds to one class loader per process: a module re-enabled
                // without restarting the app cannot load it again.
                val why = if (t is UnsatisfiedLinkError || t.message?.contains("already started") == true ||
                    t.message?.contains("cannot be retried") == true
                ) "Paddock was updated or restarted while MOTO-HUB was running. Close MOTO-HUB completely and open it again."
                else "Paddock could not start: ${t.message ?: t.javaClass.simpleName}"
                _state.value = PaddockState(stage = EngineStage.FAILED, error = why)
            }
        }
    }

    private fun step(text: String) {
        _state.value = _state.value.copy(startStep = text)
    }

    /** Once a second on the Python thread: read the state, and send my position every 10 s. */
    private fun tick() {
        val module = engine ?: return
        try {
            val json = JSONObject(module.callAttr("poll").toString())
            val parsed = StateJson.parse(json, _state.value)
            val inFront = inForeground()
            val nowMs = SystemClock.elapsedRealtime()
            if (inFront) lastInFrontAt = nowMs
            // Every relay sends announces of the whole network: held all night they kept a phone
            // awake (23/11, 40% overnight). Only while needed, unless the rider chose Always on;
            // never after the rider's Disconnect.
            // Also while a message I wrote is not delivered yet (10 min at most): the rider it rang
            // fetches it from this phone.
            val wanted = !_disconnected.value && (_alwaysOn.value || parsed.riding != null || parsed.call != null ||
                nowMs - lastInFrontAt < OFFLINE_AFTER_MILLIS || json.optBoolean("holding"))
            val next = parsed.copy(standby = !wanted && !_disconnected.value)
            val wasRiding = _state.value.riding != null
            val callBefore = _state.value.call
            _state.value = next
            if (next.call != callBefore) main.post {
                voice.update(next.call)
                if (next.call == null) holdCall(false)
            }
            StateJson.events(json).forEach {
                _events.tryEmit(it)
                notify(it)
            }
            val riding = next.riding != null
            if (wanted != relaysOn) {
                module.callAttr("set_online", wanted)
                relaysOn = wanted
                log(if (wanted) "relays connected" else "relays dropped until needed")
            }
            if (inFront && !wasInFront) module.callAttr("woke")
            holdOutbox(json.optBoolean("holding"), inFront)
            // Contract 51: my token to the rooms, their wakes to the push service, and "I am here" to
            // whoever rang me once the relays are back.
            val token = Push.token(host).orEmpty()
            if (token != sharedToken) {
                module.callAttr("set_push_token", token)
                sharedToken = token
            }
            Push.send(json.optJSONArray("wakes"), ::log) { member -> call { it.callAttr("push_gone", member) } }
            if (wanted) Push.takeRecent(System.currentTimeMillis()).forEach { wake ->
                // A call tapped while the caller still dials (90 s): call them back, which is the
                // answer. Anything else, or a call too old: tell them I am here, so what waits comes.
                if (wake.kind == "call" && System.currentTimeMillis() - wake.at < CALLBACK_WITHIN_MILLIS && voice.micAllowed()) {
                    main.post { holdCall(true) }
                    if (!answered(module.callAttr("voice_callback", wake.roomId, wake.from), "The call could not be answered.")) {
                        main.post { holdCall(false) }
                    }
                } else {
                    module.callAttr("here", wake.roomId, wake.from)
                }
            }
            wasInFront = inFront
            // A ride ends by Stop, by leaving that room, or by its leader closing it; one restored
            // after a restart starts the keep-alive here (refused if the app is in the background).
            if (riding != wasRiding) main.post { holdRide(riding) }
            if (riding && keepAliveFromBackground && inForeground()) {
                keepAliveFromBackground = false
                main.post { reopenRide() }
            }
            if (riding) sendPositionIfDue(module)
            notifyNewMessages(module, next, inFront)
            chatRoom?.let { room -> refreshChat(module, room, (next.room(room)?.unread ?: 0) > 0) }
            updateGroupRide(module, next)
        } catch (t: Throwable) {
            log("poll failed: ${t.javaClass.simpleName}")
        }
    }

    /** What MOTO-HUB's dashboards show of the room I ride with (contract 48); built after each poll, read from any thread. */
    @Volatile internal var groupRide: io.motohub.android.module.GroupRideSnapshot? = null
    private var rideChat: List<ChatMessage> = emptyList()
    private var rideChatRoom: String? = null
    private var rideChatAt = 0L
    private var rideRelaysAt = 0L

    /** On the Python thread. Reads the chat without marking it read, at most every 2 s; the relays every 10 s. */
    private fun updateGroupRide(module: PyObject, state: PaddockState) {
        if (state.stage != EngineStage.RUNNING) {
            groupRide = null
            return
        }
        val now = System.currentTimeMillis()
        val room = state.riding
        if (room == null) {
            groupRide = io.motohub.android.module.GroupRideSnapshot(
                state.rooms.firstOrNull()?.name.orEmpty(), false, state.online, 0, emptyList(), emptyList(), now
            )
            return
        }
        val tick = SystemClock.elapsedRealtime()
        if (room.id != rideChatRoom || tick - rideChatAt >= GROUP_CHAT_MILLIS) {
            rideChatRoom = room.id
            rideChatAt = tick
            rideChat = StateJson.chat(JSONObject(module.callAttr("chat", room.id, 0L, GROUP_CHAT_LIMIT).toString()))
        }
        if (tick - rideRelaysAt >= GROUP_RELAYS_MILLIS) {
            rideRelaysAt = tick
            refreshRelays()
        }
        val fix = latestFix
        val me = if (fix != null) fix.latitude to fix.longitude else myPosition()
        groupRide = io.motohub.android.module.GroupRideSnapshot(
            room = room.name, riding = true, online = state.online, relays = _relays.value.connected.size,
            members = GroupRideBuild.members(
                room, me?.first, me?.second,
                fix?.takeIf { it.hasBearing() }?.bearing?.toDouble(), fix?.takeIf { it.hasSpeed() }?.speed?.toDouble(), now / 1000
            ),
            messages = GroupRideBuild.messages(rideChat), atMillis = now
        )
    }

    /** A dashboard button's quick message to the room I ride with; null when it went, else why not. */
    internal fun sendQuickToRiding(kind: String): String? {
        val quick = QuickKind.of(kind) ?: return "Unknown message."
        val state = _state.value
        if (state.stage != EngineStage.RUNNING) return "Paddock is not running."
        val room = state.riding ?: return "Start a ride with a room in Paddock first."
        sendQuick(room.id, quick)
        return null
    }

    /** Unread count per room at the last poll; null until the first poll, which notifies nothing. Python thread only. */
    private var lastUnread: Map<String, Int>? = null

    /**
     * A notification for chat messages that arrived since the last poll, unless that room's chat
     * is on screen. The newest one is shown, with how many are unread. Python thread only.
     */
    private fun notifyNewMessages(module: PyObject, state: PaddockState, inFront: Boolean) {
        val before = lastUnread
        lastUnread = state.rooms.associate { it.id to it.unread }
        if (before == null) return
        for (room in state.rooms) {
            val added = room.unread - (before[room.id] ?: 0)
            if (added > 0) Push.fetched(room.id)
            if (added <= 0 || (inFront && chatRoom == room.id)) continue
            val latest = runCatching {
                StateJson.chat(JSONObject(module.callAttr("chat", room.id, 0L, added.coerceAtMost(5)).toString()))
            }.getOrDefault(emptyList()).lastOrNull { !it.mine && it.kind != ChatKind.EVENT } ?: continue
            val body = when (latest.kind) {
                ChatKind.QUICK -> latest.quick?.label ?: latest.text
                else -> latest.text
            }
            notifier.postMessages(
                room.id, room.name, "${latest.name}: $body", room.unread,
                urgent = latest.quick == QuickKind.EMERGENCY
            )
        }
    }

    /** Someone came into a room, or I was let in: worth a notification, not only a toast. */
    private fun notify(event: PaddockEvent) {
        val name = event.roomId?.let { _state.value.room(it)?.name } ?: "Paddock"
        when (event) {
            is PaddockEvent.MemberJoined -> notifier.post(name, event.text, event.roomId)
            is PaddockEvent.RoomJoined -> notifier.post(name, event.text, event.roomId)
            else -> Unit
        }
    }

    private fun sendPositionIfDue(module: PyObject) {
        val now = System.currentTimeMillis()
        if (now - lastPositionAt < POSITION_MILLIS) return
        val fix = latestFix?.takeIf {
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L < MAX_FIX_AGE_MILLIS
        } ?: return
        lastPositionAt = now
        module.callAttr(
            "send_position", fix.latitude, fix.longitude, if (fix.hasAltitude()) fix.altitude else 0.0,
            if (fix.hasSpeed()) fix.speed.toDouble() else 0.0,
            if (fix.hasBearing()) fix.bearing.toDouble() else 0.0,
            if (fix.hasAccuracy()) fix.accuracy.toDouble() else 0.0,
            fix.time / 1000
        )
    }

    /** After a refused start: let go of the GPS and keep-alive unless a ride is on after all. */
    private fun releaseUnlessRiding() {
        main.post { if (_state.value.riding == null) holdRide(false) }
    }

    override fun createRoom(name: String, classic: Boolean) {
        call { module -> answered(module.callAttr("create_room", name.trim().ifEmpty { "Ride" }.take(40), classic), "The room could not be created.") }
    }

    override fun joinRoom(link: String): Boolean {
        val text = link.trim()
        if (!text.startsWith("paddock://join?")) return false
        call { module ->
            val answer = JSONObject(module.callAttr("join_room", text).toString())
            if (!answer.optBoolean("ok", true)) {
                _events.tryEmit(PaddockEvent.JoinFailed(answer.optString("error", "That room code is not valid.")))
            }
        }
        return true
    }

    override fun renameRoom(roomId: String, name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        call { module -> answered(module.callAttr("rename_room", roomId, clean), "The room could not be renamed.") }
    }

    override fun leaveRoom(roomId: String) {
        call { it.callAttr("leave_room", roomId) }
    }

    override fun removeMember(roomId: String, memberHash: String) {
        call { module -> answered(module.callAttr("remove_member", roomId, memberHash), "The rider could not be removed.") }
    }

    override fun openChat(roomId: String) {
        chatRoom = roomId
        notifier.cancelMessages(roomId)
        chatLimit = CHAT_PAGE
        _chat.value = ChatView(roomId = roomId, loading = true)
        call { module -> refreshChat(module, roomId, markRead = true) }
    }

    override fun closeChat() {
        chatRoom = null
        _chat.value = ChatView()
    }

    override fun loadEarlierChat() {
        val room = chatRoom ?: return
        chatLimit += CHAT_PAGE
        call { module -> refreshChat(module, room, markRead = false) }
    }

    /** On the Python thread. Reading the chat that is on screen also marks it read. */
    private fun refreshChat(module: PyObject, roomId: String, markRead: Boolean) {
        if (chatRoom != roomId) return
        if (markRead) module.callAttr("mark_read", roomId)
        val messages = StateJson.chat(JSONObject(module.callAttr("chat", roomId, 0L, chatLimit).toString()))
        if (chatRoom == roomId) _chat.value = ChatView(roomId = roomId, messages = messages, loading = false)
    }

    override fun sendText(roomId: String, text: String) {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return
        call { module ->
            answered(module.callAttr("send_text", roomId, clean), "The message could not be sent.")
            refreshChat(module, roomId, markRead = false)
        }
    }

    override fun sendQuick(roomId: String, kind: QuickKind) {
        val here = myPosition()
        call { module ->
            answered(
                module.callAttr("send_quick", roomId, kind.wire, here?.first ?: Double.NaN, here?.second ?: Double.NaN),
                "The message could not be sent."
            )
            refreshChat(module, roomId, markRead = false)
        }
    }

    /** My latest fix: our own GPS while riding, otherwise whatever the app last had. */
    private fun myPosition(): Pair<Double, Double>? {
        latestFix?.let { return it.latitude to it.longitude }
        val fix = runCatching { host.places.livePosition() }.getOrNull() ?: return null
        return fix.latitude to fix.longitude
    }

    override fun distanceTo(member: Member): Double? {
        val lat = member.lat ?: return null
        val lon = member.lon ?: return null
        val me = myPosition() ?: return null
        val out = FloatArray(1)
        Location.distanceBetween(me.first, me.second, lat, lon, out)
        return out[0].toDouble()
    }

    override fun showPoint(lat: Double, lon: Double, label: String) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        try {
            host.showOnMap(lat, lon)
        } catch (_: LinkageError) {
            host.navigateTo(lat, lon, label)
        }
    }

    override fun callRider(roomId: String, memberHash: String) {
        if (!voice.micAllowed()) {
            _events.tryEmit(PaddockEvent.Error("MOTO-HUB may not use the microphone. Allow it to call."))
            return
        }
        main.post { holdCall(true) }
        call({ main.post { holdCall(false) } }) { module ->
            if (!answered(module.callAttr("voice_call", roomId, memberHash), "The call could not start.")) main.post { holdCall(false) }
        }
    }

    override fun answerCall() {
        main.post { holdCall(true) }
        call { module -> answered(module.callAttr("voice_answer"), "The call could not be answered.") }
    }

    override fun declineCall() {
        call { it.callAttr("voice_decline") }
    }

    override fun hangUp() {
        call { it.callAttr("voice_hangup") }
    }

    override fun setMuted(muted: Boolean) {
        voice.setMuted(muted)
        call { it.callAttr("voice_mute", muted) }
    }

    override fun setSpeaker(on: Boolean) = voice.setSpeaker(on)

    override fun startRide(roomId: String) {
        holdRide(true)
        call(onFail = ::releaseUnlessRiding) { module ->
            if (!answered(module.callAttr("start_ride", roomId), "The ride could not start.")) releaseUnlessRiding()
            else lastPositionAt = 0 // the first position goes out at once
        }
    }

    override fun stopRide() {
        holdRide(false)
        call { it.callAttr("stop_ride") }
    }

    /** True for {"ok": true} (or no "ok" at all); otherwise an Error event with the engine's sentence. */
    private fun answered(answer: PyObject?, fallback: String): Boolean {
        val json = runCatching { JSONObject(answer.toString()) }.getOrNull() ?: return true
        if (json.optBoolean("ok", true)) return true
        _events.tryEmit(PaddockEvent.Error(json.optString("error", fallback)))
        return false
    }

    /**
     * While riding: the app's foreground service and our own GPS updates. Main thread only:
     * Android starts a foreground service with location only while the app is in front.
     */
    private fun holdRide(on: Boolean) {
        if (on && keepAlive == null) {
            keepAliveFromBackground = !inForeground()
            keepAlive = runCatching { hostKeepAlive("Paddock: riding with your room") }
                .onFailure { log("keepAlive refused: ${it.javaClass.simpleName}") }.getOrNull()
        } else if (!on) {
            runCatching { keepAlive?.close() }
            keepAlive = null
            keepAliveFromBackground = false
        }
        track(on)
    }

    /**
     * The app's keep-alive, with the microphone when the app has it (contract 47): a call during a
     * ride, or one taken from the call notification, then keeps its voice with the screen off. An
     * app before contract 47 has no such call: the plain keep-alive, and calls need the screen on.
     */
    private fun hostKeepAlive(reason: String): AutoCloseable {
        if (voice.micAllowed()) {
            try {
                return host.keepAliveWithMicrophone(reason)
            } catch (_: LinkageError) {
            }
        }
        return host.keepAlive(reason)
    }

    /**
     * While a message I wrote is not delivered (10 min at most): without a foreground service Android
     * freezes MOTO-HUB in the background within seconds and the relays go quiet. Started only while
     * the app is in front, where Android allows it (the message was just written there).
     */
    private var outboxKeepAlive: AutoCloseable? = null

    private fun holdOutbox(on: Boolean, inFront: Boolean) {
        if (on && outboxKeepAlive == null && inFront) {
            main.post {
                if (outboxKeepAlive == null) outboxKeepAlive = runCatching { host.keepAlive("Paddock: delivering your message") }
                    .onFailure { log("outbox keepAlive refused: ${it.javaClass.simpleName}") }.getOrNull()
            }
        } else if (!on && outboxKeepAlive != null) {
            main.post {
                runCatching { outboxKeepAlive?.close() }
                outboxKeepAlive = null
            }
        }
    }

    /** Held for the length of a call, from the tap that starts or answers it. Main thread. */
    private var callKeepAlive: AutoCloseable? = null

    private fun holdCall(on: Boolean) {
        if (on && callKeepAlive == null) {
            callKeepAlive = runCatching { hostKeepAlive("Paddock: call in progress") }
                .onFailure { log("call keepAlive refused: ${it.javaClass.simpleName}") }.getOrNull()
        } else if (!on) {
            runCatching { callKeepAlive?.close() }
            callKeepAlive = null
        }
    }

    /** The app came to the front during a ride restored in the background: keep-alive and GPS again, with location. */
    private fun reopenRide() {
        if (_state.value.riding == null) return
        log("ride reopened in the foreground")
        holdRide(false)
        holdRide(true)
    }

    /** A visible activity: only then may a foreground service with location start. */
    private fun inForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    @SuppressLint("MissingPermission")
    private fun track(on: Boolean) {
        val manager = locations ?: return
        if (on == tracking) return
        if (!on) {
            runCatching { manager.removeUpdates(locationListener) }
            tracking = false
            latestFix = null
            return
        }
        if (host.context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _events.tryEmit(PaddockEvent.Warning("MOTO-HUB may not use your location, so your position cannot be shared. Allow precise location for MOTO-HUB in Android settings."))
            return
        }
        val provider = if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        runCatching { manager.requestLocationUpdates(provider, LOCATION_MILLIS, 0f, locationListener, Looper.getMainLooper()) }
            .onSuccess { tracking = true }
            .onFailure { log("location updates refused: ${it.javaClass.simpleName}") }
    }

    override fun shareRoomLink(context: Context, roomId: String) {
        val room = _state.value.room(roomId) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Paddock room: ${room.name}")
            putExtra(
                Intent.EXTRA_TEXT,
                "Join my Paddock room \"${room.name}\" in MOTO-HUB. Open Paddock, tap Join and paste this link:\n${room.link}"
            )
        }
        val chooser = Intent.createChooser(send, "Share the room link")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }.onFailure { log("share failed: ${it.javaClass.simpleName}") }
    }

    override fun showOnMap(roomId: String, member: Member?) {
        val target = member ?: _state.value.room(roomId)?.members
            ?.filter { !it.isMe && it.lat != null }?.maxByOrNull { it.fixAt ?: 0 }
        val lat = target?.lat
        val lon = target?.lon
        if (target == null || lat == null || lon == null) {
            _events.tryEmit(PaddockEvent.Warning("No rider has shared a position yet. They appear on the NAV map as soon as they do."))
            return
        }
        try {
            // Contract 45: the NAV map centred on the rider, no destination.
            host.showOnMap(lat, lon)
        } catch (_: LinkageError) {
            // An app before 45 has no showOnMap: the NAV opens on that rider with a route preview.
            host.navigateTo(lat, lon, target.name)
        }
    }

    private val _relays = MutableStateFlow(RelaysView(loading = true))
    override val relays: StateFlow<RelaysView> = _relays

    override fun setAutoRelay() {
        File(storage, RELAY).delete()
        call { it.callAttr("set_node", "auto", 0) }
    }

    override fun refreshRelays() {
        call { module ->
            val o = JSONObject(module.callAttr("relays").toString())
            fun list(name: String, connected: Boolean) = o.optJSONArray(name)?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { r ->
                        Relay(
                            name = r.optString("name").ifEmpty { r.optString("host") }, host = r.optString("host"),
                            port = r.optInt("port"), connected = connected, online = r.optBoolean("online", false),
                            millis = if (r.isNull("ms") || !r.has("ms")) null else r.optInt("ms"),
                            hops = if (r.isNull("hops") || !r.has("hops")) null else r.optInt("hops"),
                            status = r.optString("status").ifEmpty { if (connected) "available" else "unknown" },
                            lat = if (r.isNull("lat") || !r.has("lat")) null else r.optDouble("lat"),
                            lon = if (r.isNull("lon") || !r.has("lon")) null else r.optDouble("lon"),
                            found = r.optBoolean("discovered", false),
                            room = r.optBoolean("room", false)
                        )
                    }
                }
            }.orEmpty()
            _relays.value = RelaysView(o.optBoolean("auto", true), list("connected", true), list("discovered", false))
        }
    }

    override fun myLocation(): Pair<Double, Double>? = myPosition()
    override fun fullScreenCallsAllowed(): Boolean = voice.fullScreenAllowed()

    override fun setRelay(host: String, port: Int) {
        if (!host.trim().matches(Regex("[A-Za-z0-9.\\-]{1,253}")) || port !in 1..65535) {
            _events.tryEmit(PaddockEvent.Error("That relay address is not valid."))
            return
        }
        File(storage, RELAY).writeText("${host.trim()}:$port")
        call { it.callAttr("set_node", host.trim(), port) }
    }

    override fun resetEverything() {
        holdRide(false)
        File(storage, PROFILE).delete()
        _profile.value = null
        py.execute {
            runCatching { engine?.callAttr("reset_identity") }
                .onFailure { log("reset failed: ${it.javaClass.simpleName}") }
            // Reticulum keeps the old identity in memory until the process ends: block every call
            // until MOTO-HUB restarts, so nothing goes out under the forgotten ID.
            engine = null
            needsRestart = true
            _state.value = PaddockState(
                stage = EngineStage.FAILED,
                error = "Your rider ID is deleted. Close MOTO-HUB completely and open it again to make a new one."
            )
        }
    }

    /** The module is being unloaded: leave the room, stop GPS and the keep-alive, stop polling. */
    override fun restartMotoHub(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return
        holdRide(false)
        log("restarting MOTO-HUB")
        context.startActivity(Intent.makeRestartActivityTask(launch))
        Runtime.getRuntime().exit(0)
    }

    override fun exportIdentity(): String? = IdentityBackup.export(File(storage, IDENTITY))

    override fun importIdentity(text: String): String? {
        val key = IdentityBackup.parse(text)
            ?: return "That is not a rider ID. Paste the whole text you saved, nothing more."
        val running = engine != null || _state.value.stage == EngineStage.STARTING
        if (!running) {
            writeIdentity(key)
            log("rider ID restored")
            return null
        }
        holdRide(false)
        py.execute {
            // The rooms belong to the old ID: they are only removed from this phone (writeIdentity).
            // Leaving them would close the ones it leads for everyone.
            runCatching { engine?.callAttr("stop_ride") }
            writeIdentity(key)
            // Reticulum holds the old key in memory: nothing may go out until MOTO-HUB restarts.
            engine = null
            needsRestart = true
            _state.value = PaddockState(
                stage = EngineStage.FAILED,
                error = "Rider ID restored. Close MOTO-HUB completely and open it again to use it."
            )
            log("rider ID restored, restart needed")
        }
        return null
    }

    private fun writeIdentity(key: ByteArray) {
        val tmp = File(storage, "$IDENTITY.tmp")
        tmp.writeBytes(key)
        if (!tmp.renameTo(File(storage, IDENTITY))) error("could not save the rider ID")
        // The room belonged to the old ID.
        File(storage, "room.json").delete()
        File(storage, "ride.json").delete()
        File(storage, "rooms").deleteRecursively()
    }

    /**
     * The module is being unloaded (an update, a removal): stop GPS, the keep-alive and polling.
     * Rooms are NOT left: they are permanent, and an update must find them where they were.
     */
    fun release() {
        voice.release()
        runCatching { callKeepAlive?.close() }
        runCatching { outboxKeepAlive?.close() }
        runCatching { locations?.removeUpdates(locationListener) }
        runCatching { keepAlive?.close() }
        keepAlive = null
        // Python itself cannot be stopped; it stays idle until the app's process ends.
        py.shutdown()
    }

    private fun call(onFail: () -> Unit = {}, block: (PyObject) -> Unit) {
        py.execute {
            val module = engine
            if (module == null) {
                _events.tryEmit(PaddockEvent.Error("Paddock is still starting. Try again in a moment."))
                onFail()
                return@execute
            }
            try {
                block(module)
            } catch (t: Throwable) {
                onFail()
                // Only the type: the app's log goes into diagnostics reports, and Python's message
                // may carry a name or a room link.
                log("call failed: ${t.javaClass.simpleName}")
                _events.tryEmit(PaddockEvent.Error("Something went wrong: ${t.message ?: t.javaClass.simpleName}"))
            }
        }
    }

    private fun relay(): Pair<String, Int> {
        var saved = File(storage, RELAY).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        // The old Settings page came filled with rns.kin.earth and a Save button: that file is the
        // old default saved, not a choice. Gone, so the rider gets the automatic relays.
        if (saved == "$OLD_DEFAULT_HOST:$DEFAULT_PORT") {
            File(storage, RELAY).delete()
            saved = ""
        }
        // Nothing chosen: the fastest public relays, picked by Python at each start.
        if (saved.isEmpty()) return "auto" to 0
        val host = saved.substringBeforeLast(':', "").ifEmpty { DEFAULT_HOST }
        val port = saved.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        return host to port
    }

    private fun readProfile(): Profile? = runCatching {
        val o = JSONObject(File(storage, PROFILE).readText())
        Profile(o.getString("name"), o.optString("color", RiderColors.first()), o.optString("bike", ""))
    }.getOrNull()?.takeIf { it.name.isNotBlank() }

    private fun log(line: String) = host.log.log("PADDOCK $line")

    private companion object {
        // Columba's default community node; the rider can change it in Settings.
        const val DEFAULT_HOST = "rns.kin.earth"
        const val OLD_DEFAULT_HOST = "rns.kin.earth"
        const val DEFAULT_PORT = 4242
        const val PROFILE = "profile.json"
        const val IDENTITY = "identity"
        const val RELAY = "relay.txt"
        const val ALWAYS_ON = "always_on"
        const val DISCONNECTED = "disconnected"
        /** After leaving MOTO-HUB, the relays stay this long: a quick look at another app keeps them. */
        const val OFFLINE_AFTER_MILLIS = 60_000L
        const val POLL_MILLIS = 1000L
        /** The caller dials 90 s after ringing this phone (paddock_voice.WOKEN_RING_S), less what it took to get here. */
        const val CALLBACK_WITHIN_MILLIS = 80_000L
        const val POSITION_MILLIS = 10_000L
        const val CHAT_PAGE = 60
        const val GROUP_CHAT_LIMIT = 8L
        const val GROUP_CHAT_MILLIS = 2_000L
        const val GROUP_RELAYS_MILLIS = 10_000L
        const val MAX_TEXT = 2000
        const val LOCATION_MILLIS = 5_000L
        /** An older fix is not sent: the room would see a rider standing still who is not. */
        const val MAX_FIX_AGE_MILLIS = 30_000L
    }
}
