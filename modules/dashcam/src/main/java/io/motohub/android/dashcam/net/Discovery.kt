// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Locale

/** Something that announced itself on the network, by mDNS (Bonjour) or SSDP (UPnP). */
class Heard(
    val host: String,
    /** The name it gave itself, or empty. */
    val name: String,
    /** "mDNS" or "UPnP", for the log. */
    val via: String,
    /** The port of the RTSP service it announced over mDNS; null when it named none. */
    val rtspPort: Int?,
    /** Its UPnP device description, where its friendly name is: SSDP only. */
    val location: String?
)

/** What one mDNS answer says about a camera service: its instance name, and the RTSP port if any. */
internal class MdnsAnswer(val name: String, val rtspPort: Int?)

/**
 * The two ways devices announce themselves besides ONVIF: mDNS, asked for the services cameras
 * publish, and SSDP, asked for every UPnP device. Neither proves a camera - the search still asks
 * each address what it serves - but they find cameras outside the /24 the sweep covers, on a port
 * the sweep does not knock on, and give them their own names.
 *
 * Both are asked from an ordinary port, so both answer unicast to it (RFC 6762 §6.7 for mDNS):
 * no multicast lock, and no permission the app would have to hold for this module.
 */
object Discovery {
    private const val SSDP_GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val RTSP_SERVICE = "_rtsp._tcp.local"

    /** The mDNS services cameras publish: RTSP itself, and Axis cameras' own. */
    internal val MDNS_SERVICES = listOf(RTSP_SERVICE, "_axis-video._tcp.local")

    internal val SSDP_SEARCH = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_GROUP:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\nST: ssdp:all\r\nUSER-AGENT: Android UPnP/1.1 MOTO-HUB-Dashcam/1\r\n\r\n"

    /** Asks on each of [nets] and gathers what answers for [waitMs]. */
    fun listen(nets: List<LocalNet>, waitMs: Int, log: (String) -> Unit): List<Heard> {
        val found = LinkedHashMap<String, Heard>()
        val sockets = ArrayList<MulticastSocket>()
        try {
            val ssdp = SSDP_SEARCH.toByteArray(Charsets.ISO_8859_1)
            val mdns = mdnsQuery(MDNS_SERVICES)
            for (net in nets) {
                val s = runCatching { MulticastSocket() }.getOrNull() ?: continue
                try {
                    net.network?.bindSocket(s)
                    NetworkInterface.getByName(net.interfaceName)?.let { s.networkInterface = it }
                    s.timeToLive = 2
                    s.soTimeout = 200
                    // Twice each: it is UDP.
                    repeat(2) {
                        s.send(DatagramPacket(ssdp, ssdp.size, InetAddress.getByName(SSDP_GROUP), SSDP_PORT))
                        s.send(DatagramPacket(mdns, mdns.size, InetAddress.getByName(MDNS_GROUP), MDNS_PORT))
                    }
                    sockets.add(s)
                } catch (e: Exception) {
                    s.close()
                    log("scan: mDNS/SSDP on ${net.label}: ${e.message}")
                }
            }
            val buffer = ByteArray(9000)
            val deadline = System.currentTimeMillis() + waitMs
            while (sockets.isNotEmpty() && System.currentTimeMillis() < deadline) {
                for (s in sockets) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        s.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        continue
                    }
                    val host = packet.address?.hostAddress ?: continue
                    val heard = if (packet.port == SSDP_PORT || startsWithHttp(buffer, packet.length)) {
                        parseSsdp(String(buffer, 0, packet.length, Charsets.ISO_8859_1))?.let { Heard(host, "", "UPnP", null, it) }
                    } else {
                        runCatching { parseMdns(buffer, packet.length) }.getOrNull()?.let { Heard(host, it.name, "mDNS", it.rtspPort, null) }
                    } ?: continue
                    val before = found[host]
                    // mDNS knows more than SSDP (a name, a port): it wins; the UPnP description stays.
                    found[host] = if (before == null) heard else Heard(
                        host,
                        before.name.ifEmpty { heard.name },
                        if (before.via == heard.via) before.via else "mDNS · UPnP",
                        before.rtspPort ?: heard.rtspPort,
                        before.location ?: heard.location
                    )
                }
            }
        } finally {
            for (s in sockets) runCatching { s.close() }
        }
        return ArrayList(found.values)
    }

    private fun startsWithHttp(b: ByteArray, length: Int) =
        length > 5 && b[0] == 'H'.code.toByte() && b[1] == 'T'.code.toByte() && b[2] == 'T'.code.toByte() && b[3] == 'P'.code.toByte()

    /** The LOCATION of an SSDP answer, or null when it is not one. */
    internal fun parseSsdp(text: String): String? {
        if (!text.startsWith("HTTP/1.1 200", ignoreCase = true) && !text.startsWith("NOTIFY", ignoreCase = true)) return null
        // Split on \n alone: the \r left on each line goes with the trim of name and value.
        for (line in text.split('\n')) {
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("location", ignoreCase = true)) {
                return line.substring(colon + 1).trim().takeIf { it.startsWith("http", ignoreCase = true) }
            }
        }
        return null
    }

    /**
     * The device's own name out of its UPnP description at [location]: maker and model when it
     * gives them, its friendly name otherwise. Null when it cannot be read.
     */
    fun describe(location: String, bind: (Socket) -> Unit): String? = runCatching {
        val u = URI(location)
        val path = u.rawPath.orEmpty().ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
        val xml = CameraHttp(u.host, bind, if (u.port > 0) u.port else 80).get(path, 2000).text
        val model = listOfNotNull(Onvif.value(xml, "manufacturer"), Onvif.value(xml, "modelName"))
            .filter { it.isNotBlank() }.joinToString(" ")
        model.ifEmpty { Onvif.value(xml, "friendlyName").orEmpty() }.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------------------------------------------------------------- mDNS messages

    /** A query for the PTR records of [services], asking for unicast answers (the QU bit). */
    internal fun mdnsQuery(services: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        // ID 0, standard query, one question per service, no answers.
        out.write(byteArrayOf(0, 0, 0, 0, 0, services.size.toByte(), 0, 0, 0, 0, 0, 0))
        for (service in services) {
            for (label in service.split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            out.write(byteArrayOf(0, TYPE_PTR.toByte(), 0x80.toByte(), 1))
        }
        return out.toByteArray()
    }

    /**
     * The camera service in one mDNS answer: the instance name its PTR record points at, and the
     * port of its RTSP SRV record. Null when the message is a question or names none of [MDNS_SERVICES].
     */
    internal fun parseMdns(data: ByteArray, length: Int): MdnsAnswer? {
        if (length < 12 || (data[2].toInt() and 0x80) == 0) return null
        fun u16(at: Int) = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
        val questions = u16(4)
        val records = u16(6) + u16(8) + u16(10)
        var at = 12
        repeat(questions) { at = readName(data, length, at).second + 4 }
        var name: String? = null
        var port: Int? = null
        repeat(records) {
            val (owner, next) = readName(data, length, at)
            if (next + 10 > length) return null
            val type = u16(next)
            val size = u16(next + 8)
            val rdata = next + 10
            if (rdata + size > length) return null
            val service = owner.lowercase(Locale.ROOT)
            when {
                type == TYPE_PTR && service in MDNS_SERVICES ->
                    name = readName(data, length, rdata).first.substringBefore("._")
                type == TYPE_SRV && service.endsWith(".$RTSP_SERVICE") && size >= 6 -> {
                    port = u16(rdata + 4)
                    if (name == null) name = owner.substringBefore("._")
                }
            }
            at = rdata + size
        }
        val found = name ?: return null
        return MdnsAnswer(found, port)
    }

    /** A name at [start], following compression pointers; the offset after it in the message. */
    private fun readName(data: ByteArray, length: Int, start: Int): Pair<String, Int> {
        val labels = StringBuilder()
        var at = start
        var end = -1
        var jumps = 0
        while (true) {
            if (at >= length) throw IllegalArgumentException("name runs past the message")
            val n = data[at].toInt() and 0xff
            when {
                n == 0 -> {
                    if (end < 0) end = at + 1
                    return labels.toString() to end
                }
                (n and 0xC0) == 0xC0 -> {
                    if (at + 1 >= length || ++jumps > 16) throw IllegalArgumentException("bad name pointer")
                    if (end < 0) end = at + 2
                    at = ((n and 0x3F) shl 8) or (data[at + 1].toInt() and 0xff)
                }
                else -> {
                    if (at + 1 + n > length) throw IllegalArgumentException("label runs past the message")
                    if (labels.isNotEmpty()) labels.append('.')
                    labels.append(String(data, at + 1, n, Charsets.UTF_8))
                    at += 1 + n
                }
            }
        }
    }

    private const val TYPE_PTR = 12
    private const val TYPE_SRV = 33
}
