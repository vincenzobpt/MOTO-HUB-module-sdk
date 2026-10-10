// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.InputStream
import java.io.OutputStream

object WppMessageType {
    const val START_REQUEST = 1
    const val INFO_REQUEST = 2
    const val INFO_RESPONSE = 3
    const val VERSION_REQUEST = 4
    const val VERSION_RESPONSE = 5
    const val CONNECT_STATUS = 6
    const val START_RESPONSE = 7
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
}

data class WppCredentials(
    val ssid: String,
    val key: String,
    val bssid: String = "",
    val securityMode: Int = 0,
    val accessPointType: Int = 0
)

data class WppEndpoint(val ipAddress: String, val port: Int)

/**
 * The phone side of the Wi-Fi Projection Protocol, spoken over Bluetooth RFCOMM before the phone
 * joins the head unit's Wi-Fi. The head unit sends its version and start requests, names its AP
 * and hands over the credentials; this answers each in turn and returns the TCP endpoint to dial.
 */
class WppPhoneHandshake(
    private val input: InputStream,
    private val output: OutputStream,
    private val log: (String) -> Unit
) {
    class Outcome(val success: Boolean, val endpoint: WppEndpoint?, val credentials: WppCredentials?, val detail: String)

    fun run(localIp: String): Outcome {
        try {
            var endpoint: WppEndpoint? = null
            var credentials: WppCredentials? = null

            while (true) {
                val header = ByteArray(WppFraming.HEADER_SIZE)
                if (readFully(input, header) < 0) return fail("RFCOMM stream closed before WPP header")

                val payloadSize = WppFraming.decodePayloadSize(header)
                val type = WppFraming.decodeType(header)
                val payload = if (payloadSize > 0) {
                    ByteArray(payloadSize).also { if (readFully(input, it) < 0) return fail("RFCOMM stream closed mid-payload") }
                } else ByteArray(0)

                log("WPP recv type=$type (${typeName(type)}) len=$payloadSize ${hex(payload, 48)}")

                when (type) {
                    WppMessageType.VERSION_REQUEST -> {
                        val (major, minor) = parseVersionRequest(payload)
                        log("HU version $major.$minor")
                        sendVersionResponse(major, minor)
                        log("Sent WifiVersionResponse")
                    }

                    WppMessageType.START_REQUEST -> {
                        endpoint = parseStartRequest(payload)
                        log("HU endpoint ${endpoint.ipAddress}:${endpoint.port}")
                        sendInfoRequest()
                        log("Sent WifiInfoRequest (asking for credentials)")
                    }

                    WppMessageType.INFO_RESPONSE -> {
                        credentials = parseInfoResponse(payload)
                        log("Credentials received: ssid=${credentials.ssid} bssid=${credentials.bssid}")
                        sendStartResponse(localIp)
                        log("Sent WifiStartResponse")
                        return Outcome(true, endpoint, credentials,
                            "WPP handshake complete: endpoint=${endpoint?.ipAddress}:${endpoint?.port} ssid=${credentials.ssid}")
                    }

                    WppMessageType.PING_REQUEST -> {
                        val frame = WppFraming.encodeFrame(payload, WppMessageType.PING_RESPONSE)
                        output.write(frame)
                        output.flush()
                    }

                    else -> log("Ignoring WPP type $type")
                }
            }
        } catch (e: Exception) {
            return fail("WPP handshake exception: ${e.message}")
        }
    }

    fun sendConnectStatus(success: Boolean) {
        val status = if (success) 0 else 1
        val size = CodedOutputStream.computeInt32Size(1, status)
        val buf = ByteArray(size)
        val cos = CodedOutputStream.newInstance(buf)
        cos.writeInt32(1, status)
        cos.flush()
        val frame = WppFraming.encodeFrame(buf, WppMessageType.CONNECT_STATUS)
        output.write(frame)
        output.flush()
        log("Sent WifiConnectStatus(status=$status)")
    }

    private fun sendVersionResponse(major: Int, minor: Int) {
        val size = CodedOutputStream.computeInt32Size(1, major) +
            CodedOutputStream.computeInt32Size(2, minor) +
            CodedOutputStream.computeStringSize(3, "MOTO-HUB") +
            CodedOutputStream.computeInt32Size(4, 0)
        val buf = ByteArray(size)
        val cos = CodedOutputStream.newInstance(buf)
        cos.writeInt32(1, major)
        cos.writeInt32(2, minor)
        cos.writeString(3, "MOTO-HUB")
        cos.writeInt32(4, 0)
        cos.flush()
        val frame = WppFraming.encodeFrame(buf, WppMessageType.VERSION_RESPONSE)
        output.write(frame)
        output.flush()
    }

    private fun sendInfoRequest() {
        val frame = WppFraming.encodeFrame(ByteArray(0), WppMessageType.INFO_REQUEST)
        output.write(frame)
        output.flush()
    }

    private fun sendStartResponse(localIp: String) {
        val size = CodedOutputStream.computeStringSize(1, localIp) +
            CodedOutputStream.computeInt32Size(3, 0)
        val buf = ByteArray(size)
        val cos = CodedOutputStream.newInstance(buf)
        cos.writeString(1, localIp)
        cos.writeInt32(3, 0)
        cos.flush()
        val frame = WppFraming.encodeFrame(buf, WppMessageType.START_RESPONSE)
        output.write(frame)
        output.flush()
    }

    private fun parseVersionRequest(payload: ByteArray): Pair<Int, Int> {
        if (payload.isEmpty()) return 1 to 0
        val cis = CodedInputStream.newInstance(payload)
        var major = 1; var minor = 0
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            when (tag ushr 3) {
                1 -> major = cis.readInt32()
                2 -> minor = cis.readInt32()
                else -> cis.skipField(tag)
            }
        }
        return major to minor
    }

    private fun parseStartRequest(payload: ByteArray): WppEndpoint {
        val cis = CodedInputStream.newInstance(payload)
        var ip = ""; var port = 0
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            when (tag ushr 3) {
                1 -> ip = cis.readString()
                2 -> port = cis.readInt32()
                else -> cis.skipField(tag)
            }
        }
        return WppEndpoint(ip, port)
    }

    private fun parseInfoResponse(payload: ByteArray): WppCredentials {
        val cis = CodedInputStream.newInstance(payload)
        var ssid = ""; var key = ""; var bssid = ""; var security = 0; var apType = 0
        while (!cis.isAtEnd) {
            val tag = cis.readTag()
            when (tag ushr 3) {
                1 -> ssid = cis.readString()
                2 -> key = cis.readString()
                3 -> bssid = cis.readString()
                4 -> security = cis.readEnum()
                5 -> apType = cis.readEnum()
                else -> cis.skipField(tag)
            }
        }
        return WppCredentials(ssid, key, bssid, security, apType)
    }

    private fun fail(detail: String): Outcome = Outcome(false, null, null, detail)

    companion object {
        private fun readFully(input: InputStream, buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return -1
                off += n
            }
            return off
        }

        private fun typeName(type: Int): String = when (type) {
            WppMessageType.START_REQUEST -> "WifiStartRequest"
            WppMessageType.INFO_REQUEST -> "WifiInfoRequest"
            WppMessageType.INFO_RESPONSE -> "WifiInfoResponse"
            WppMessageType.VERSION_REQUEST -> "WifiVersionRequest"
            WppMessageType.VERSION_RESPONSE -> "WifiVersionResponse"
            WppMessageType.CONNECT_STATUS -> "WifiConnectStatus"
            WppMessageType.START_RESPONSE -> "WifiStartResponse"
            WppMessageType.PING_REQUEST -> "WifiPingRequest"
            WppMessageType.PING_RESPONSE -> "WifiPingResponse"
            else -> "unknown"
        }

        private fun hex(bytes: ByteArray, max: Int): String {
            if (bytes.isEmpty()) return "[]"
            val n = minOf(bytes.size, max)
            val sb = StringBuilder(n * 3 + 8)
            sb.append('[')
            for (i in 0 until n) {
                if (i > 0) sb.append(' ')
                val v = bytes[i].toInt() and 0xFF
                sb.append("0123456789abcdef"[v ushr 4])
                sb.append("0123456789abcdef"[v and 0xF])
            }
            if (bytes.size > n) sb.append(" …+").append(bytes.size - n)
            sb.append(']')
            return sb.toString()
        }
    }
}
