// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import io.motohub.android.module.ModuleAccessoryStreams
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MOTO-HUB between a real head unit and Google's Android Auto, reading everything that crosses.
 *
 * The head unit put the phone into accessory mode and MOTO-HUB holds that accessory. At the same
 * time it dials Android Auto's own head unit server on the loopback - the port Android Auto opens
 * when the rider starts "Head unit server" from its developer settings, and the one [AaReceiver]
 * already uses to make Android Auto believe MOTO-HUB is a head unit. So Android Auto thinks it is
 * talking to a head unit, the head unit thinks it is talking to a phone, and the bytes go through
 * here. It is what the wireless dongles do, minus the wireless.
 *
 * Why this exists rather than another guess: [AapPhoneHandshake] opened the same accessory 70 ms
 * after it appeared and got nothing back in 31 seconds, in either role, while Android Auto drives
 * that exact cable without trouble. What the phone actually writes first is therefore the only
 * unknown left, and it cannot be reasoned out - only read.
 *
 * Deliberately byte-for-byte and blind to meaning. Parsing here would be a second guess layered on
 * the first; the point is to record what is really said, including the parts we would have got
 * wrong. Only the opening of each direction is dumped, because the rest is video.
 */
object AapProxyBridge {

    private const val HEAD_UNIT_SERVER_PORT = 5277
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 1_000

    /**
     * How long to hold the bridge open. Long enough for Android Auto to get through version, TLS
     * and service discovery and start sending video, short enough that a probe left running does
     * not sit on the rider's head unit.
     */
    private const val RUN_FOR_MS = 60_000L

    /**
     * How long to give the head unit the first word before standing in for it.
     *
     * Barely a pause, because Android Auto drops a head unit that has not spoken within about a
     * second and a half: a 1500 ms wait lost the race outright, and the priming never happened.
     * A head unit that does speak in this window is forwarded normally and never primed.
     */
    private const val PRIME_AFTER_MS = 200L

    /** Bytes of each direction to dump. The opening exchange is what is being read here. */
    private const val DUMP_BYTES = 96

    class Outcome(val success: Boolean, val detail: String)

    fun run(streams: ModuleAccessoryStreams, log: (String) -> Unit): Outcome {
        val socket = try {
            Socket().apply {
                connect(InetSocketAddress("127.0.0.1", HEAD_UNIT_SERVER_PORT), CONNECT_TIMEOUT_MS)
                tcpNoDelay = true
            }
        } catch (e: Exception) {
            return Outcome(
                false,
                "Could not reach Android Auto's head unit server on :$HEAD_UNIT_SERVER_PORT " +
                    "(${e.message}). Start it from Android Auto's developer settings first."
            )
        }
        log("Connected to Android Auto's head unit server on :$HEAD_UNIT_SERVER_PORT.")

        val running = AtomicBoolean(true)
        val phoneToUnit = AtomicLong(0)
        val unitToPhone = AtomicLong(0)
        val threads = mutableListOf<Thread>()

        try {
            val toUnit = socket.getInputStream()
            val fromUnit = streams.input

            // Android Auto -> head unit. This is the direction that carries the answer: whatever
            // lands here first is what MOTO-HUB should have been writing on that accessory.
            threads += pump(
                name = "aap-bridge-phone-to-unit",
                running = running,
                label = "PHONE -> UNIT",
                counter = phoneToUnit,
                log = log,
                read = { buffer -> toUnit.read(buffer) },
                write = { buffer, length -> streams.write(buffer.copyOf(length)) }
            )

            threads += pump(
                name = "aap-bridge-unit-to-phone",
                running = running,
                label = "UNIT -> PHONE",
                counter = unitToPhone,
                log = log,
                // A plain blocking read. Polling available() first, as this used to, never read
                // anything: the accessory device cannot report pending bytes, and only receives
                // while a read is queued (see UsbAoaAccessoryConnection). The thread is a daemon
                // and the read ends when the app closes the accessory after the bridge returns.
                read = { buffer -> fromUnit.read(buffer) },
                write = { buffer, length -> socket.getOutputStream().write(buffer, 0, length) }
            )

            // Prime the phone side if the head unit will not.
            //
            // Android Auto's head unit server expects whoever dialled it to open the conversation
            // - AaReceiver connects and sends VERSION_REQUEST immediately, and that is the whole
            // reason it works. A bridge that only forwards says nothing, so Android Auto closed
            // the socket after 1.6 seconds without a byte. Since this head unit never opens the
            // conversation either, somebody has to, and MOTO-HUB is standing in the head unit's
            // place on this side. What comes back is Android Auto answering as a phone - which is
            // exactly the traffic this bridge exists to read.
            Thread.sleep(PRIME_AFTER_MS)
            if (running.get() && unitToPhone.get() == 0L && phoneToUnit.get() == 0L) {
                val version = Messages.versionRequest
                log("The head unit said nothing. Priming Android Auto with ${hex(version, version.size)}")
                runCatching { socket.getOutputStream().write(version) }
                    .onFailure { log("Priming failed: ${it.message}") }
            }

            val deadline = System.currentTimeMillis() + RUN_FOR_MS
            while (running.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(1_000)
            }

            val up = phoneToUnit.get()
            val down = unitToPhone.get()
            return Outcome(
                up > 0 && down > 0,
                when {
                    up == 0L && down == 0L ->
                        "Nothing crossed in either direction. Android Auto connected but never " +
                            "spoke, and neither did the head unit."
                    down == 0L ->
                        "Android Auto sent $up bytes to the head unit and the head unit answered " +
                            "nothing. It ignores Google's own phone exactly as it ignored ours, " +
                            "so what it is waiting for is not on this pipe at all - but the dump " +
                            "above is what a real phone writes, which is what we came for."
                    up == 0L ->
                        "The head unit sent $down bytes and Android Auto answered nothing."
                    else ->
                        "Both sides talked: $up bytes phone->unit, $down bytes unit->phone. The " +
                            "opening of each direction is dumped above."
                }
            )
        } catch (e: Exception) {
            return Outcome(false, "Bridge failed: ${e.message}")
        } finally {
            running.set(false)
            threads.forEach { it.interrupt() }
            runCatching { socket.close() }
        }
    }

    /**
     * One direction of the bridge, dumping the first [DUMP_BYTES] it carries.
     *
     * A read of zero is not an end of stream here - the accessory side returns it whenever there
     * is simply nothing yet - so only a negative stops the pump.
     */
    private fun pump(
        name: String,
        running: AtomicBoolean,
        label: String,
        counter: AtomicLong,
        log: (String) -> Unit,
        read: (ByteArray) -> Int,
        write: (ByteArray, Int) -> Unit
    ): Thread = Thread({
        val buffer = ByteArray(16 * 1024)
        var dumped = 0
        while (running.get()) {
            val n = try {
                read(buffer)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                if (running.get()) log("$label: read ended (${e.message}).")
                break
            }
            if (n < 0) {
                if (running.get()) log("$label: the far end closed.")
                break
            }
            if (n == 0) continue

            if (dumped < DUMP_BYTES) {
                val take = minOf(n, DUMP_BYTES - dumped)
                log("$label ${hex(buffer, take)}")
                dumped += take
            }
            counter.addAndGet(n.toLong())

            try {
                write(buffer, n)
            } catch (e: Exception) {
                if (running.get()) log("$label: write failed (${e.message}).")
                break
            }
        }
        running.set(false)
    }, name).apply { isDaemon = true; start() }

    private fun hex(buffer: ByteArray, length: Int): String =
        buildString(length * 3) {
            for (i in 0 until length) {
                if (i > 0) append(' ')
                append("%02x".format(buffer[i]))
            }
        }
}
