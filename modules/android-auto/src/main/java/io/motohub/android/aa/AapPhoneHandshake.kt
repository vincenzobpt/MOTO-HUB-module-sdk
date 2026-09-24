// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import android.content.Context
import android.os.SystemClock
import io.motohub.android.aa.proto.Control
import io.motohub.android.aaplugin.AaIdentityProvider
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MOTO-HUB in the AAP *phone* role: the physical head unit is the video sink and MOTO-HUB is the
 * source - the exact inverse of [AapTransport]/[AaReceiver], which make MOTO-HUB masquerade as a
 * head unit for Google's own Android Auto app.
 *
 * This is the de-risking probe, not a session: it takes the handshake as far as service discovery
 * and reports what the head unit said. Everything after that (channel opens, video setup, the
 * encoder) is only worth building once this answers yes.
 *
 * Two things are independent here, and conflating them is what cost the first two attempts:
 *
 *  - **The AAP role** is fixed by which end owns the screen. The head unit answers service
 *    discovery ([ServiceDiscoveryResponse] builds that reply on the head-unit side), so the phone
 *    is the side that *asks*. That never changes, and this class always asks.
 *  - **Who drives** follows from who opened the transport, not from the AAP role. When MOTO-HUB
 *    plays head unit it dials Google's Android Auto app over a socket, and as the dialler it
 *    sends VERSION_REQUEST, acts as TLS client and sends AUTH_COMPLETE. Over AOA the phone is the
 *    side that opens the accessory - so here MOTO-HUB is the dialler, in the phone's AAP role.
 *
 * An earlier revision took the whole of the first bullet and inverted it too, sat listening for
 * fifteen seconds, and found the stream closed by the time it tried to speak: this head unit
 * creates the accessory and then waits, tearing the link down if nothing arrives. So this speaks
 * first and adapts - whichever end sends the version request is the one that drives the TLS
 * handshake, and the probe reports which it turned out to be.
 *
 * Deliberately not built on [AapTransport]: its handshake is hardwired to answering as a head
 * unit, and the service-discovery half inverts here.
 */
object AapPhoneHandshake {
    private const val MSG_VERSION_REQUEST = 1
    private const val MSG_VERSION_RESPONSE = 2
    private const val MSG_AUTH_COMPLETE = 4

    /**
     * Short on purpose: the head unit closes an accessory nobody uses within a few seconds, so a
     * generous wait here does not buy patience, it just misses the window.
     */
    private const val LISTEN_MS = 6_000

    /** Wide enough to span more than one of the head unit's AOA retry cycles (~20s each). */
    private const val SILENT_WATCH_MS = 25_000

    /** Only long enough to notice a frame already in flight, never long enough to wait for one. */
    private const val STRAY_DRAIN_MS = 300

    private const val REPLY_TIMEOUT_MS = 8_000

    class Outcome(val success: Boolean, val detail: String)

    /** One AAP frame as it came off the wire, header parsed, payload unread. */
    private class Frame(val channel: Int, val flags: Int, val type: Int, val payload: ByteArray) {
        override fun toString(): String =
            "ch=$channel(${Channel.name(channel)}) flags=0x${flags.toString(16)} " +
                "type=$type(${MsgType.name(type, channel)}) len=${payload.size}"
    }

    fun run(
        @Suppress("UNUSED_PARAMETER") context: Context,
        identity: AaIdentityProvider,
        connection: AccessoryConnection,
        log: (String) -> Unit
    ): Outcome {
        val pump = AtomicBoolean(false)
        var pumpThread: Thread? = null
        try {
            // Speak immediately, then listen. Waiting first cost the whole earlier attempt: the
            // head unit creates the accessory, stays quiet, and tears the link down within
            // seconds - by the time a 15s listen expired the stream was already closed and the
            // fallback never ran on a live link. Sending costs nothing if the unit was going to
            // speak anyway; its frame simply arrives next.
            val request = Messages.versionRequest
            if (connection.sendBlocking(request, request.size, 2000) < 0) {
                return Outcome(false, "Could not send VERSION_REQUEST: the accessory stream is closed.")
            }
            log("Sent VERSION_REQUEST; waiting up to ${LISTEN_MS / 1000}s for the head unit.")

            var first = readFrame(connection, LISTEN_MS)
            if (first == null) {
                // Keep listening in silence rather than giving up here. aa-proxy-rs - a working
                // dongle that plays this exact role, a USB gadget in accessory mode facing a car -
                // documents the head unit as the side that opens with a ten-byte frame, and this
                // unit re-runs its AOA handshake every twenty seconds or so. So the question worth
                // asking is not "did it answer us" but "does it ever speak at all", and answering
                // it needs a window wider than one of its cycles.
                log("Nothing in ${LISTEN_MS / 1000}s. Listening in silence for ${SILENT_WATCH_MS / 1000}s more.")
                first = readFrame(connection, SILENT_WATCH_MS)
                    ?: return Outcome(
                        false,
                        "The head unit put no byte on the wire in " +
                            "${(LISTEN_MS + SILENT_WATCH_MS) / 1000}s, and ignored a " +
                            "VERSION_REQUEST. Android Auto does drive this cable, so the link " +
                            "works: this unit does not start AAP with us at all."
                    )
                log("It spoke on its own, late: $first")
            }
            log("First frame from the head unit: $first")

            // Who drives the TLS handshake follows from who opened the conversation, and this is
            // the fact the probe is here to establish rather than assume. If the unit answered
            // our VERSION_REQUEST, we are the initiator and therefore the TLS client - the same
            // shape AapTransport has when it dials Google's Android Auto app over a socket. If
            // the unit sent a VERSION_REQUEST of its own, it drives, and we answer as the server.
            val tlsClient: Boolean
            when {
                first.channel == Channel.ID_CTR && first.type == MSG_VERSION_RESPONSE -> {
                    tlsClient = true
                    log("It answered our request: MOTO-HUB drives, so TLS client.")
                }
                first.channel == Channel.ID_CTR && first.type == MSG_VERSION_REQUEST -> {
                    tlsClient = false
                    // Echo back the version it offered rather than asserting one of our own: it
                    // named the pair it wants and the status field is the only thing to answer.
                    val major = beShort(first.payload, 0, fallback = 1)
                    val minor = beShort(first.payload, 2, fallback = 1)
                    val response = Messages.createRawMessage(
                        Channel.ID_CTR,
                        3,
                        MSG_VERSION_RESPONSE,
                        byteArrayOf(
                            (major shr 8).toByte(), (major and 0xFF).toByte(),
                            (minor shr 8).toByte(), (minor and 0xFF).toByte(),
                            0, 0 // status: matched
                        )
                    )
                    if (connection.sendBlocking(response, response.size, 2000) < 0) {
                        return Outcome(false, "Failed to send VERSION_RESPONSE.")
                    }
                    log("It opened with its own request: the head unit drives, so TLS server. " +
                        "Answered version $major.$minor.")
                }
                else -> return Outcome(
                    false,
                    "Expected a version frame on the control channel, got $first. " +
                        "The head unit is speaking, but not the protocol this probe knows."
                )
            }

            // Both sides may have opened at once, and the loser's frame is still queued. Drained
            // here rather than left for the TLS reader, which parses raw frames and would take a
            // stray version message for a ClientHello.
            var stray = readFrame(connection, STRAY_DRAIN_MS)
            while (stray != null) {
                log("Drained a queued frame before TLS: $stray")
                stray = readFrame(connection, STRAY_DRAIN_MS)
            }

            if (!identity.isAvailable()) {
                return Outcome(
                    false,
                    "Version exchange succeeded, but no Android Auto identity is bundled, so " +
                        "there is no certificate to present. Build with " +
                        "-PincludeAndroidAutoIdentity=true."
                )
            }

            // Either way MOTO-HUB presents a certificate - AAP is mutually authenticated - and
            // the one it has is a head-unit identity being used as a phone's. That is precisely
            // the question this probe exists to answer: a unit that checks the role, or checks
            // against Google's root at all, will reject it here and say so.
            val ssl = AapSslContext(SingleKeyKeyManager(identity), useClientMode = tlsClient)
            log("Starting the TLS handshake in ${if (tlsClient) "client" else "server"} mode.")
            if (!ssl.performHandshake(connection)) {
                return Outcome(
                    false,
                    "TLS handshake failed in ${if (tlsClient) "client" else "server"} mode. The " +
                        "head unit most likely rejected the certificate MOTO-HUB presented - it " +
                        "is a head-unit identity, and the unit may require a phone one."
                )
            }
            ssl.postHandshakeReset()
            log("TLS handshake complete: the head unit accepted MOTO-HUB's certificate.")

            val inbox = LinkedBlockingQueue<AapMessage>()
            val reader = AapReadMultipleMessages(
                connection,
                ssl,
                object : AapMessageHandler {
                    override fun handle(message: AapMessage) {
                        inbox.put(message)
                    }
                }
            )
            pump.set(true)
            pumpThread = Thread({
                while (pump.get()) {
                    if (reader.read() < 0) pump.set(false)
                }
            }, "AapPhoneHandshake-pump").apply { isDaemon = true; start() }

            // AUTH_COMPLETE goes the same way the version request went: the side that opened the
            // conversation declares the channel authenticated. AapTransport sends Messages.statusOk
            // here in exactly this position when MOTO-HUB is the one who dialled.
            if (tlsClient) {
                val statusOk = Messages.statusOk
                if (connection.sendBlocking(statusOk, statusOk.size, 2000) < 0) {
                    return Outcome(false, "Failed to send AUTH_COMPLETE.")
                }
                log("Sent AUTH_COMPLETE.")
            } else {
                val auth = inbox.poll(REPLY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                    ?: return Outcome(false, "Timed out waiting for AUTH_COMPLETE after TLS.")
                if (auth.type != MSG_AUTH_COMPLETE) {
                    log("Expected AUTH_COMPLETE, got type=${auth.type}; continuing anyway.")
                } else {
                    log("Received AUTH_COMPLETE.")
                }
            }

            val discovery = AapMessage(
                Channel.ID_CTR,
                Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE,
                Control.ServiceDiscoveryRequest.newBuilder()
                    .setPhoneName("MOTO-HUB")
                    .setPhoneBrand("MOTO-HUB")
                    .build()
            )
            if (!sendEncrypted(connection, ssl, discovery)) {
                return Outcome(false, "Failed to send SERVICE_DISCOVERY_REQUEST.")
            }
            log("Sent SERVICE_DISCOVERY_REQUEST.")

            val reply = inbox.poll(REPLY_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                ?: return Outcome(false, "Timed out waiting for SERVICE_DISCOVERY_RESPONSE.")
            if (reply.type != Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE) {
                return Outcome(
                    false,
                    "Expected SERVICE_DISCOVERY_RESPONSE, got type=${reply.type} " +
                        "(${MsgType.name(reply.type, reply.channel)})."
                )
            }
            val parsed = reply.parse(Control.ServiceDiscoveryResponse.newBuilder()).build()
            for (service in parsed.servicesList) {
                log("  service id=${service.id} ${describe(service)}")
            }
            return Outcome(
                true,
                "The head unit accepted MOTO-HUB as a phone: make=${parsed.make} " +
                    "model=${parsed.model} headUnit=${parsed.headUnitMake}/${parsed.headUnitModel} " +
                    "services=${parsed.servicesCount}."
            )
        } catch (e: Exception) {
            return Outcome(false, "Exception during the phone-role handshake: ${e.message}")
        } finally {
            pump.set(false)
            pumpThread?.interrupt()
        }
    }

    /**
     * One AAP frame, or null if the deadline passes before a header lands.
     *
     * Reads the header with the full deadline and the payload with a short one: a head unit that
     * has begun a frame finishes it promptly, and the long wait is for it to begin one at all.
     */
    private fun readFrame(connection: AccessoryConnection, timeoutMs: Int): Frame? {
        val header = ByteArray(6)
        val started = SystemClock.elapsedRealtime()
        if (connection.recvBlocking(header, 6, timeoutMs, true) != 6) return null
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val type = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        val payloadLength = (length - MsgType.SIZE).coerceIn(0, Messages.DEF_BUFFER_LENGTH)
        val payload = ByteArray(payloadLength)
        if (payloadLength > 0 &&
            connection.recvBlocking(payload, payloadLength, 2000, true) != payloadLength
        ) {
            return null
        }
        AaLog.d("AapPhoneHandshake: frame after ${SystemClock.elapsedRealtime() - started}ms")
        return Frame(header[0].toInt() and 0xFF, header[1].toInt() and 0xFF, type, payload)
    }

    /** A big-endian 16-bit field, or [fallback] when the payload is too short to carry one. */
    private fun beShort(payload: ByteArray, offset: Int, fallback: Int): Int =
        if (payload.size < offset + 2) {
            fallback
        } else {
            ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        }

    /** Which side of a discovered service is which, in the words the response uses. */
    private fun describe(service: Control.Service): String = buildString {
        if (service.hasMediaSinkService()) append("media sink ")
        if (service.hasMediaSourceService()) append("media source ")
        if (service.hasInputSourceService()) append("input source ")
        if (service.hasSensorSourceService()) append("sensor source ")
        if (isEmpty()) append("(no side declared)")
    }.trim()

    private fun sendEncrypted(
        connection: AccessoryConnection,
        ssl: AapSsl,
        message: AapMessage
    ): Boolean {
        val ba = ssl.encrypt(
            AapMessage.HEADER_SIZE,
            message.size - AapMessage.HEADER_SIZE,
            message.data
        ) ?: return false
        ba.data[0] = message.data[0]
        ba.data[1] = message.data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)
        return connection.sendBlocking(ba.data, ba.limit, 2000) >= 0
    }
}
