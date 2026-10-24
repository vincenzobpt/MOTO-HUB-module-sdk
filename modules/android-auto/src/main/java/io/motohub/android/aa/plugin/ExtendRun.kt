// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// The Extend run - MOTO-HUB projecting onto an external head unit - and its reconnects.
package io.motohub.android.aa.plugin

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.motohub.android.aa.AapPhoneHandshake
import io.motohub.android.aa.phone.WirelessHeadUnitTransport
import io.motohub.android.module.MotoHubModuleHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A run gives up once this long has passed without a session with the head unit. */
internal const val EXTEND_GIVE_UP_MS = 3 * 60_000L

/** Wait before the next attempt after [failures] failed ones in a row, or null to give up. */
internal fun extendReconnectDelayMillis(failures: Int, sinceGoodMs: Long): Long? = when {
    sinceGoodMs >= EXTEND_GIVE_UP_MS -> null
    // A dropped session, or a unit that was not ready yet (one answered a second tap 40 s later).
    failures <= 1 -> 3_000L
    else -> 10_000L
}

/**
 * One Extend run per process, kept here rather than in its page: the page is left seconds after
 * the tap, and a run that only lived there could neither reconnect nor be stopped later.
 *
 * A run reconnects on its own. Ticket E67E-6B89 (OPPO, 2026-10-23): a healthy session died when
 * ColorOS killed the app, and nothing started it again until the rider went back to the module
 * page. Now a link that drops is dialled again, and a run a kill cut off resumes when the process
 * comes back ([resumeIfCutOff]). It ends when the rider stops it, when the head unit says goodbye
 * or the app ends the dashboard, or after [EXTEND_GIVE_UP_MS] without a session.
 */
internal object ExtendRun {
    private const val RESUME_WINDOW_MS = 3 * 60_000L
    private const val MARKER_TOUCH_MS = 30_000L
    private const val LOG_TAIL = 8_000
    private const val MARKER = "extend-run"

    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<String?>(null)
        private set
    var logText by mutableStateOf("")
        private set

    /** Runs started on top of a stuck one each held a Bluetooth link to the unit. */
    private val active = AtomicBoolean(false)
    @Volatile private var stopRequested = false
    @Volatile private var transport: WirelessHeadUnitTransport.Result? = null
    @Volatile private var marker: File? = null
    @Volatile private var job: Job? = null

    /** False when a run is already going. */
    fun start(host: MotoHubModuleHost, device: BluetoothDevice): Boolean {
        if (!active.compareAndSet(false, true)) return false
        stopRequested = false
        running = true
        result = null
        logText = ""
        // Its age tells a process that comes back after a kill whether the run is still worth resuming.
        val file = File(host.storage, MARKER).also { marker = it }
        runCatching { file.writeText(device.address) }
        // Taken here, on the tap: Android only lets a foreground app start the foreground
        // service, and the rider leaves the app seconds later.
        val keepAlive = host.keepAlive("Ride Dashboard on the head unit")
        var lastTouch = 0L
        val sink: (String) -> Unit = { line ->
            host.log.log("[Extend] $line")
            logText = (logText + line + "\n").takeLast(LOG_TAIL)
            val now = System.currentTimeMillis()
            if (now - lastTouch >= MARKER_TOUCH_MS) {
                lastTouch = now
                file.setLastModified(now)
            }
        }
        job = CoroutineScope(Dispatchers.IO).launch {
            var lastGood = SystemClock.elapsedRealtime()
            var failures = 0
            var why = "the run ended"
            try {
                while (true) {
                    val outcome = attempt(host, device, sink)
                    result = if (outcome.success) "OK: ${outcome.detail}" else "FAIL: ${outcome.detail}"
                    // The verdict only reached the screen, so a report never said how a run ended.
                    host.log.log("[Extend] Result: $result")
                    if (stopRequested) break
                    if (outcome.endedForGood) {
                        why = "the session was ended on purpose"
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (outcome.success) {
                        lastGood = now
                        failures = 0
                    } else {
                        failures++
                    }
                    val wait = extendReconnectDelayMillis(failures, now - lastGood)
                    if (wait == null) {
                        why = "no session with the head unit for ${EXTEND_GIVE_UP_MS / 60_000} minutes"
                        break
                    }
                    sink("Reconnecting in ${wait / 1000} s.")
                    delay(wait)
                }
            } finally {
                if (stopRequested) why = "stopped by the rider"
                host.log.log("[Extend] Not reconnecting: $why.")
                runCatching { file.delete() }
                keepAlive.close()
                running = false
                active.set(false)
            }
        }
        return true
    }

    /** Ends the run and its reconnects; a session on the head unit closes with its link. */
    fun stop() {
        stopRequested = true
        // Now, not when the run winds down: a module loaded after this one must not resume it.
        runCatching { marker?.delete() }
        transport?.close()
        job?.cancel()
    }

    /** Called as the module loads: picks up a run that a kill of the process cut off. */
    fun resumeIfCutOff(host: MotoHubModuleHost) {
        val file = File(host.storage, MARKER)
        if (!file.exists()) return
        val age = System.currentTimeMillis() - file.lastModified()
        val address = runCatching { file.readText().trim() }.getOrNull()
        val adapter = host.context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = address?.let { runCatching { adapter?.getRemoteDevice(it) }.getOrNull() }
        if (age > RESUME_WINDOW_MS || device == null) {
            runCatching { file.delete() }
            return
        }
        host.log.log("[Extend] Resuming the head unit run the app lost ${age / 1000} s ago.")
        // shortcut: a process that comes back in the background may be refused the foreground
        // service and the head unit's Wi-Fi; the rider opening the app gives the next attempt both.
        start(host, device)
    }

    private fun attempt(
        host: MotoHubModuleHost,
        device: BluetoothDevice,
        sink: (String) -> Unit
    ): AapPhoneHandshake.Outcome {
        val link = WirelessHeadUnitTransport.connect(host.context, device, sink)
        transport = link
        try {
            // A stop that came while connecting must not start a session on the link it left.
            if (stopRequested) return AapPhoneHandshake.Outcome(false, "stopped by the rider")
            if (link.connection == null) return AapPhoneHandshake.Outcome(false, link.detail)
            return AapPhoneHandshake.run(
                host.context, ModuleIdentity, link.connection, sink, host,
                listenFirstMs = AapPhoneHandshake.WIRELESS_LISTEN_FIRST_MS
            )
        } finally {
            transport = null
            // Each run would otherwise leave its socket open and its Wi-Fi request live.
            link.close()
        }
    }
}
