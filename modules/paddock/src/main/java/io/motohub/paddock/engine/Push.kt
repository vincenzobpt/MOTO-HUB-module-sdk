// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.engine

import io.motohub.android.module.ModulePushReceiver
import io.motohub.android.module.MotoHubModuleHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The push doorbell (MOTO-HUB contract 51). A rider whose MOTO-HUB is in the background has parked
 * Paddock's relays, so a message or a call cannot reach them over Reticulum. Their phone is rung
 * instead: Python queues a wake (a message not delivered in 15 s, a call with no link in 4 s), this
 * sends it to push.motohub.techub.eu, which hands it to Firebase. The push carries only the room id,
 * the kind and a prefix of the sender's id: the message itself still travels over Reticulum once the
 * rider opens MOTO-HUB.
 */
internal object Push {
    private const val ENDPOINT = "https://push.motohub.techub.eu/v1/wake"
    private val http = Executors.newSingleThreadExecutor { Thread(it, "paddock-push").apply { isDaemon = true } }

    /** This phone's token, or null on an app before contract 51 or without Firebase. */
    fun token(host: MotoHubModuleHost): String? = try {
        host.pushToken()
    } catch (_: LinkageError) {
        null
    }

    /** Sends Python's queued wakes; [gone] gets the member whose token the service says is dead. */
    fun send(wakes: JSONArray?, log: (String) -> Unit, gone: (String) -> Unit) {
        if (wakes == null || wakes.length() == 0) return
        for (i in 0 until wakes.length()) {
            val w = wakes.optJSONObject(i) ?: continue
            http.execute {
                val code = runCatching {
                    val body = JSONObject().put("t", w.optString("t")).put("k", w.optString("k"))
                        .put("r", w.optString("r")).put("f", w.optString("f")).toString()
                    (URL(ENDPOINT).openConnection() as HttpURLConnection).run {
                        requestMethod = "POST"
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        doOutput = true
                        setRequestProperty("content-type", "application/json")
                        outputStream.use { it.write(body.toByteArray()) }
                        responseCode.also { disconnect() }
                    }
                }.getOrElse { -1 }
                // Only the kind and the answer: a token or a room id has no place in the app's log.
                log("rang a rider (${w.optString("k")}): $code")
                if (code == 410) gone(w.optString("to"))
            }
        }
    }

    /**
     * The pushes that woke this phone, on disk (push_wakes.json): Android may end MOTO-HUB between the
     * push and the rider's tap, and both what the chat shows and the "I am here" to the sender must
     * still be there after it. Read once by [attach], before anything else asks.
     */
    data class Wake(
        val roomId: String, val from: String, val kind: String, val at: Long, val name: String,
        /** "I am here" or the call-back already done. */
        val said: Boolean = false,
        /** The message came: the chat no longer says it is fetching it. */
        val fetchedIt: Boolean = false
    )

    private var file: File? = null
    private val wakes = ArrayList<Wake>()

    /**
     * Rooms whose chat a push said has a new message not fetched yet: room id -> (sender's name, when).
     * The chat shows that it is fetching it for [FETCH_SHOWN_MILLIS] after it opened, then that the
     * sender is offline (every wait is visible); cleared when the message comes.
     */
    val fetching = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    const val FETCH_SHOWN_MILLIS = 45_000L
    private const val KEEP_MILLIS = 15 * 60_000L

    fun attach(storage: File) = synchronized(wakes) {
        if (file != null) return
        file = File(storage, "push_wakes.json")
        runCatching {
            val a = JSONArray(file!!.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                wakes.add(Wake(o.getString("r"), o.getString("f"), o.getString("k"), o.getLong("at"),
                    o.optString("n", "A rider"), o.optBoolean("said"), o.optBoolean("got")))
            }
        }
        changed()
    }

    /** Under the lock: drop old wakes, save, and publish what the chats show. */
    private fun changed() {
        val now = System.currentTimeMillis()
        wakes.removeAll { now - it.at > KEEP_MILLIS }
        file?.let { f ->
            runCatching {
                val a = JSONArray()
                wakes.forEach {
                    a.put(JSONObject().put("r", it.roomId).put("f", it.from).put("k", it.kind).put("at", it.at)
                        .put("n", it.name).put("said", it.said).put("got", it.fetchedIt))
                }
                File(f.path + ".tmp").apply { writeText(a.toString()) }.renameTo(f)
            }
        }
        fetching.value = wakes.filter { it.kind == "msg" && !it.fetchedIt }.associate { it.roomId to (it.name to it.at) }
    }

    fun remember(wake: Wake) = synchronized(wakes) {
        wakes.removeAll { it.roomId == wake.roomId && it.from == wake.from }
        wakes.add(wake)
        changed()
    }

    fun fetched(roomId: String) = synchronized(wakes) {
        if (wakes.none { it.roomId == roomId && !it.fetchedIt }) return
        for (i in wakes.indices) if (wakes[i].roomId == roomId) wakes[i] = wakes[i].copy(fetchedIt = true)
        changed()
    }

    /** The wakes not acted on yet (last 15 minutes), once. */
    fun takeRecent(now: Long): List<Wake> = synchronized(wakes) {
        val todo = wakes.filter { !it.said && now - it.at < KEEP_MILLIS }
        if (todo.isEmpty()) return emptyList()
        for (i in wakes.indices) if (!wakes[i].said) wakes[i] = wakes[i].copy(said = true)
        changed()
        todo
    }
}

/**
 * The app's side of a push for Paddock: reads the room from its file (no Python, no network: the
 * process may have just started for this) and shows the notification. A tap opens that room's chat,
 * or the room for a call, where Paddock reconnects and the message or the call comes in.
 */
internal class PushReceiver(private val storage: File, private val notifier: Notifier) : ModulePushReceiver {
    override fun onPush(data: Map<String, String>): Boolean {
        val roomId = data["r"]?.takeIf { ROOM.matches(it) } ?: return false
        val from = data["f"]?.takeIf { FROM.matches(it) } ?: return false
        val kind = data["k"] ?: return false
        val room = runCatching { JSONObject(File(storage, "rooms/$roomId.json").readText()) }.getOrNull() ?: return false
        val roomName = room.optString("name").ifBlank { "Paddock" }
        val members = room.optJSONArray("members")
        val name = (0 until (members?.length() ?: 0)).map { members!!.getJSONObject(it) }
            .firstOrNull { it.optString("hash").startsWith(from) }?.optString("name")?.ifBlank { null } ?: "A rider"
        Push.attach(storage)
        Push.remember(Push.Wake(roomId, from, kind, System.currentTimeMillis(), name))
        return when (kind) {
            "msg" -> notifier.postWakeMessage(roomId, roomName, name)
            "call" -> notifier.postWakeCall(roomId, roomName, name)
            else -> false
        }
    }

    private companion object {
        val ROOM = Regex("[0-9a-f]{32}")
        val FROM = Regex("[0-9a-f]{8,16}")
    }
}
