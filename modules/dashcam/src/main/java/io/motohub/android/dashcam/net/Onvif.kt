// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.regex.Pattern

/** An ONVIF device that answered the discovery probe: where its device service is, and its name. */
class OnvifDevice(val host: String, val xaddr: String, val name: String)

/** What an ONVIF camera says it is. */
class OnvifInfo(val maker: String, val model: String, val firmware: String)

/**
 * ONVIF, the standard IP cameras speak: found by WS-Discovery (a SOAP probe multicast to
 * 239.255.255.250:3702), then asked over SOAP for the RTSP address of its stream - the one thing a
 * guess at the path cannot be sure of.
 *
 * Only what this module needs: who it is, and where its picture is. The login goes as
 * WS-UsernameToken in the message and, for the cameras that want it there instead, as HTTP Digest
 * through [CameraHttp].
 */
object Onvif {
    private const val GROUP = "239.255.255.250"
    private const val PORT = 3702
    private const val SOAP = "application/soap+xml; charset=utf-8"

    // ---------------------------------------------------------------- discovery

    internal fun probe(messageId: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\"" +
            " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\"" +
            " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\"" +
            " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<e:Header><w:MessageID>uuid:$messageId</w:MessageID>" +
            "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>" +
            "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"

    /**
     * The device service address and a name out of one ProbeMatch, or null when it is not one.
     * An IPv4 address is preferred among the several a camera may list.
     */
    internal fun parseProbeMatch(xml: String): Pair<String, String>? {
        val xaddrs = value(xml, "XAddrs") ?: return null
        val all = xaddrs.split(' ').map { it.trim() }.filter { it.startsWith("http", ignoreCase = true) }
        val xaddr = all.firstOrNull { !it.contains('[') } ?: all.firstOrNull() ?: return null
        val scopes = value(xml, "Scopes").orEmpty().split(' ')
        fun scope(kind: String) = scopes.firstOrNull { it.contains("onvif.org/$kind/") }
            ?.substringAfter("onvif.org/$kind/")?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val name = listOfNotNull(scope("name"), scope("hardware")).filter { it.isNotBlank() }.joinToString(" ")
        return xaddr to name
    }

    /**
     * Sends the probe on each of [nets] and gathers the answers for [waitMs]. Answers come back
     * unicast to the probing socket, so no multicast lock is needed to hear them.
     */
    fun discover(nets: List<LocalNet>, waitMs: Int, log: (String) -> Unit): List<OnvifDevice> {
        val found = LinkedHashMap<String, OnvifDevice>()
        val sockets = ArrayList<MulticastSocket>()
        try {
            for (net in nets) {
                val s = runCatching { MulticastSocket() }.getOrNull() ?: continue
                try {
                    net.network?.bindSocket(s)
                    NetworkInterface.getByName(net.interfaceName)?.let { s.networkInterface = it }
                    s.timeToLive = 2
                    s.soTimeout = 200
                    val bytes = probe(UUID.randomUUID().toString()).toByteArray(Charsets.UTF_8)
                    // Twice: it is UDP, and a camera busy with its stream may miss one.
                    repeat(2) { s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), PORT)) }
                    sockets.add(s)
                } catch (e: Exception) {
                    s.close()
                    log("scan: ONVIF probe on ${net.label}: ${e.message}")
                }
            }
            val buffer = ByteArray(16 * 1024)
            val deadline = System.currentTimeMillis() + waitMs
            while (sockets.isNotEmpty() && System.currentTimeMillis() < deadline) {
                for (s in sockets) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        continue
                    }
                    val host = packet.address?.hostAddress ?: continue
                    val match = parseProbeMatch(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                    if (!found.containsKey(host)) found[host] = OnvifDevice(host, match.first, match.second)
                }
            }
        } finally {
            for (s in sockets) runCatching { s.close() }
        }
        return ArrayList(found.values)
    }

    // ---------------------------------------------------------------- SOAP

    /** Who the camera is. Many answer this without a login; a [LoginRequired] says this one does not. */
    fun info(http: CameraHttp, path: String, login: Credentials?): OnvifInfo {
        val xml = call(http, path, "<tds:GetDeviceInformation/>", login, clockOffset(http, path))
        return OnvifInfo(value(xml, "Manufacturer").orEmpty(), value(xml, "Model").orEmpty(), value(xml, "FirmwareVersion").orEmpty())
    }

    /**
     * The RTSP address of the camera's first profile - its main stream - with the camera's own
     * host in it: behind a hotspot or a router the address a camera believes it has is not always
     * the one the phone reaches it at.
     */
    fun streamUri(http: CameraHttp, path: String, login: Credentials?): String {
        val offset = clockOffset(http, path)
        val capabilities = runCatching {
            call(http, path, "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>", login, offset)
        }.onFailure { if (it is LoginRequired) throw it }.getOrNull()
        val mediaPath = capabilities?.let { value(it, "Media") }?.let { value(it, "XAddr") }
            ?.let { runCatching { URI(it.trim()).rawPath }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: path
        val profiles = call(http, mediaPath, "<trt:GetProfiles/>", login, offset)
        val token = firstProfileToken(profiles) ?: throw IOException("The camera lists no ONVIF profile.")
        val answer = call(
            http, mediaPath,
            "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
                "<trt:ProfileToken>${escape(token)}</trt:ProfileToken></trt:GetStreamUri>",
            login, offset
        )
        val uri = value(answer, "Uri")?.let { unescape(it) } ?: throw IOException("The camera gave no stream address.")
        return onHost(uri, http.host)
    }

    internal fun firstProfileToken(xml: String): String? {
        val m = Pattern.compile("<(?:[\\w-]+:)?Profiles\\b[^>]*\\btoken=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(xml)
        return if (m.find()) m.group(1) else null
    }

    /** [uri] with its host replaced by [host], port, path and query kept. */
    internal fun onHost(uri: String, host: String): String {
        val u = runCatching { URI(uri) }.getOrNull() ?: return uri
        if (u.host == null || u.host == host) return uri
        return buildString {
            append(u.scheme ?: "rtsp").append("://").append(host)
            if (u.port > 0) append(':').append(u.port)
            append(u.rawPath.orEmpty())
            u.rawQuery?.let { append('?').append(it) }
        }
    }

    /**
     * How far the camera's clock is from the phone's. The login's timestamp is checked against the
     * camera's clock, and a camera on a hotspot with no time server can be hours or years off.
     */
    private fun clockOffset(http: CameraHttp, path: String): Long = runCatching {
        val xml = call(http, path, "<tds:GetSystemDateAndTime/>", null, 0L)
        val utc = value(xml, "UTCDateTime") ?: throw IOException("no clock in the answer")
        fun n(tag: String) = value(utc, tag)?.toIntOrNull() ?: throw IOException("no $tag")
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(n("Year"), n("Month") - 1, n("Day"), n("Hour"), n("Minute"), n("Second"))
        c.timeInMillis - System.currentTimeMillis()
    }.getOrDefault(0L)

    private fun call(http: CameraHttp, path: String, body: String, login: Credentials?, clockOffsetMs: Long): String {
        val response = http.post(path, SOAP, envelope(body, login, clockOffsetMs).toByteArray(Charsets.UTF_8))
        val text = response.text
        if (response.code == 401 || text.contains("NotAuthorized") || text.contains("FailedAuthentication")) {
            throw LoginRequired(rejected = login != null)
        }
        if (!text.contains("Envelope")) throw IOException("Not an ONVIF answer (HTTP ${response.code}).")
        if (Pattern.compile("<(?:[\\w-]+:)?Fault>", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
            throw IOException("The camera turned down an ONVIF request (HTTP ${response.code}).")
        }
        return text
    }

    private fun envelope(body: String, login: Credentials?, clockOffsetMs: Long): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"" +
            " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"" +
            " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"" +
            " xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
            (login?.let { "<s:Header>${usernameToken(it, clockOffsetMs)}</s:Header>" } ?: "") +
            "<s:Body>$body</s:Body></s:Envelope>"

    /** WS-UsernameToken with a password digest: Base64(SHA-1(nonce + created + password)). */
    private fun usernameToken(login: Credentials, clockOffsetMs: Long): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(System.currentTimeMillis() + clockOffsetMs))
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce)
        sha.update(created.toByteArray(Charsets.UTF_8))
        sha.update(login.password.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
            "<UsernameToken><Username>${escape(login.user)}</Username>" +
            "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" +
            b64.encodeToString(sha.digest()) + "</Password>" +
            "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-soap-message-security-1.0#Base64Binary\">" +
            b64.encodeToString(nonce) + "</Nonce>" +
            "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">$created</Created>" +
            "</UsernameToken></Security>"
    }

    // ---------------------------------------------------------------- reading the XML

    /** `<ns:tag attr…>value</ns:tag>`, whatever the namespace prefix, first occurrence. */
    internal fun value(xml: String, tag: String): String? {
        val m = Pattern.compile("<(?:[\\w-]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?$tag>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(xml)
        return if (m.find()) m.group(1)?.trim() else null
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
}
