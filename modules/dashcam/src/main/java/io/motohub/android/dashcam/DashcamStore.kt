// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam

import java.io.File
import java.util.Properties

/** A camera set up in the module, as the list of cameras shows it. */
class SavedCamera(val id: String, val name: String, val lan: Boolean, val where: String)

/**
 * The cameras the rider has set up - for each, its Wi-Fi name and password or, for a camera on the
 * phone's own network, its address, how to show it and its login - kept in the module's own
 * directory - the only place a module writes - so they survive app updates and leave with the
 * module when it is removed.
 *
 * One camera is [active] at a time, and the fields below are that one's: the connection reads
 * them without knowing there are others. Each camera's keys carry its id (`c2.ssid`).
 */
class DashcamStore(private val dir: File) {
    private val file = File(dir, "camera.properties")
    private val props = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
        // Up to 0.5.0 the module kept one camera, under bare keys: it becomes camera 1.
        if (getProperty(ACTIVE) == null) {
            for (k in FIELDS) getProperty(k)?.let { setProperty("c1.$k", it); remove(k) }
            setProperty(ACTIVE, "1")
        }
    }

    /** The camera set up before [startNew], to go back to if the new one is never finished. */
    private var previous: String? = null

    val active: String get() = props.getProperty(ACTIVE, "1")

    private fun read(field: String, id: String = active): String = props.getProperty("c$id.$field", "")
    private fun write(field: String, value: String) { props.setProperty("c$active.$field", value) }

    var ssid: String
        get() = read("ssid")
        set(value) { write("ssid", value.trim()) }

    var password: String
        get() = read("password")
        set(value) { write("password", value) }

    /** The camera is on the phone's network (Wi-Fi or hotspot), not on a Wi-Fi of its own. */
    var lan: Boolean
        get() = read("mode") == "lan"
        set(value) { write("mode", if (value) "lan" else "wifi") }

    var lanHost: String
        get() = read("lanHost")
        set(value) { write("lanHost", value.trim()) }

    /** The dashcam family it answered as, or empty for a camera that offers only its stream. */
    var lanFamily: String
        get() = read("lanFamily")
        set(value) { write("lanFamily", value) }

    /** A stream-only camera's RTSP address, with no login in it. */
    var lanUrl: String
        get() = read("lanUrl")
        set(value) { write("lanUrl", value) }

    /** Its ONVIF device service, when it has one: asked again for the stream if the address goes stale. */
    var lanOnvif: String
        get() = read("lanOnvif")
        set(value) { write("lanOnvif", value) }

    var lanName: String
        get() = read("lanName")
        set(value) { write("lanName", value) }

    /** The camera's own login, for the IP cameras that ask; empty user = none. */
    var lanUser: String
        get() = read("lanUser")
        set(value) { write("lanUser", value) }

    var lanPassword: String
        get() = read("lanPassword")
        set(value) { write("lanPassword", value) }

    /** Whether the active camera has been set up, either way. */
    val configured: Boolean get() = isConfigured(active)

    private fun isConfigured(id: String): Boolean =
        if (read("mode", id) == "lan") read("lanHost", id).isNotBlank() else read("ssid", id).isNotBlank()

    /** The name the camera gave when it last answered ("LINGTUO S1"), kept for the list of cameras. */
    fun setLabel(id: String, label: String) {
        if (label.isNotBlank() && isConfigured(id)) props.setProperty("c$id.label", label)
    }

    /** What the rider calls the active camera before it has answered. */
    val name: String get() = nameOf(active)

    private fun nameOf(id: String): String {
        val label = read("label", id)
        if (label.isNotBlank()) return label
        return if (read("mode", id) == "lan") read("lanName", id).ifEmpty { read("lanHost", id) } else read("ssid", id)
    }

    private fun ids(): List<String> {
        val out = ArrayList<String>()
        for (k in props.stringPropertyNames()) {
            if (!k.startsWith("c")) continue
            val dot = k.indexOf('.')
            if (dot < 2) continue
            val id = k.substring(1, dot)
            if (id.toIntOrNull() != null && id !in out) out.add(id)
        }
        java.util.Collections.sort(out, Comparator { a, b -> a.toInt() - b.toInt() })
        return out
    }

    /** Every camera set up, in the order they were added. */
    fun cameras(): List<SavedCamera> = ids().filter { isConfigured(it) }.map { id ->
        val lan = read("mode", id) == "lan"
        SavedCamera(id, nameOf(id), lan, if (lan) read("lanHost", id) else read("ssid", id))
    }

    fun select(id: String) {
        previous = null
        props.setProperty(ACTIVE, id)
    }

    /** Makes a new, empty camera the active one, for the setup pages to fill. */
    fun startNew() {
        dropUnfinished()
        previous = active
        val next = (ids().maxOfOrNull { it.toInt() } ?: 0) + 1
        props.setProperty(ACTIVE, next.toString())
    }

    /**
     * A new camera left before it was set up is forgotten, and the one in use before comes back.
     * True when that happened.
     */
    fun dropUnfinished(): Boolean {
        if (configured) return false
        val dropped = active
        val others = cameras()
        if (others.isEmpty()) return false
        forget(dropped)
        val back = previous?.takeIf { p -> others.any { it.id == p } } ?: others[0].id
        select(back)
        return true
    }

    /** Forgets [id]; if it was the active one, the first of the others takes its place. */
    fun remove(id: String) {
        forget(id)
        if (id == active) select(cameras().firstOrNull()?.id ?: "1")
    }

    private fun forget(id: String) {
        for (k in props.stringPropertyNames()) if (k.startsWith("c$id.")) props.remove(k)
    }

    fun save() {
        dir.mkdirs()
        val tmp = File(dir, "camera.properties.tmp")
        tmp.outputStream().use { props.store(it, "Dashcam module") }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Where downloaded files go: inside the module's directory, removed with the module. */
    val downloads: File get() = File(dir, "downloads").apply { mkdirs() }

    private companion object {
        const val ACTIVE = "active"
        val FIELDS = listOf("ssid", "password", "mode", "lanHost", "lanFamily", "lanUrl", "lanOnvif", "lanName", "lanUser", "lanPassword")
    }
}
