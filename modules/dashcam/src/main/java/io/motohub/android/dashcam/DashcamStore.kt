// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam

import java.io.File
import java.util.Properties

/**
 * The camera's Wi-Fi name and password, kept in the module's own directory - the only place a
 * module writes - so they survive app updates and leave with the module when it is removed.
 */
class DashcamStore(private val dir: File) {
    private val file = File(dir, "camera.properties")
    private val props = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use { load(it) } }
    }

    var ssid: String
        get() = props.getProperty("ssid", "")
        set(value) { props.setProperty("ssid", value.trim()) }

    var password: String
        get() = props.getProperty("password", "")
        set(value) { props.setProperty("password", value) }

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
}
