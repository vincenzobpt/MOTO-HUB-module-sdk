// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * A network the phone is on, where a camera may be too: the Wi-Fi it has joined, or the hotspot
 * it is making. [phone] is the phone's own address there, [prefix] the length of its subnet mask.
 */
class LocalNet(
    /** What the rider reads: "Wi-Fi", "Ethernet", "Hotspot". */
    val kind: String,
    val interfaceName: String,
    /**
     * The network to bind sockets to. Null for the phone's hotspot (and USB or Bluetooth tethering):
     * Android has no Network object for those, and routes to their subnet by itself.
     */
    val network: Network?,
    val phone: Inet4Address,
    val prefix: Int
) {
    val bind: (Socket) -> Unit = { s -> network?.bindSocket(s) }

    fun contains(address: Inet4Address): Boolean =
        LocalNetworks.sameSubnet(LocalNetworks.toInt(phone), LocalNetworks.toInt(address), prefix)

    val label: String get() = "$kind ${phone.hostAddress}/$prefix"
}

/**
 * The phone's local networks, read fresh on every call - Wi-Fi comes and goes, the hotspot is
 * switched on and off - and where each camera address belongs among them.
 *
 * Unlike [CameraWifi] this joins nothing and changes nothing: a camera on the same network as the
 * phone is reached over the connection the phone already has.
 */
class LocalNetworks(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION") // allNetworks: a one-off read, where a callback would be all ceremony.
    fun list(): List<LocalNet> {
        val out = ArrayList<LocalNet>()
        val owned = HashSet<String>()
        for (n in connectivity.allNetworks) {
            val lp = connectivity.getLinkProperties(n) ?: continue
            lp.interfaceName?.let { owned.add(it) }
            val caps = connectivity.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> continue
            }
            val link = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            out.add(LocalNet(kind, lp.interfaceName.orEmpty(), n, link.address as Inet4Address, link.prefixLength))
        }
        // The hotspot: an interface with a private address that belongs to no network Android
        // knows (cellular, Wi-Fi and VPN all do). Wi-Fi Direct is left out: on the motorcycle that
        // link is the bike's own.
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (iface in Collections.list(interfaces)) {
            val name = iface.name
            if (name in owned || name.startsWith("p2p") || name.startsWith("dummy") || name.startsWith("aware")) continue
            if (!runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)) continue
            for (a in iface.interfaceAddresses) {
                val address = a.address as? Inet4Address ?: continue
                if (!address.isSiteLocalAddress) continue
                out.add(LocalNet("Hotspot", name, null, address, a.networkPrefixLength.toInt()))
                break
            }
        }
        return out
    }

    /** The network [host] sits on, or null when it is not on any of the phone's subnets. */
    fun forHost(host: String): LocalNet? {
        val address = literal(host) ?: return null
        return list().firstOrNull { it.contains(address) }
    }

    /**
     * How to reach [host]: bound to the Wi-Fi it is on - which need not be the phone's default
     * network, a Wi-Fi without internet never is - or left to Android's routing (the hotspot, a
     * camera behind a router or a VPN).
     */
    fun bindFor(host: String): (Socket) -> Unit = forHost(host)?.bind ?: {}

    /** The network on which [host] is the phone's own address - typed, by mistake, for the camera's. */
    fun ownedBy(host: String): LocalNet? = list().firstOrNull { it.phone.hostAddress == host }

    companion object {
        /** The first address of the subnet [phone] is on: where a camera's own Wi-Fi, and most routers, sit. */
        fun firstHost(phone: Inet4Address, prefix: Int): String {
            val p = prefix.coerceIn(8, 30)
            return toText((toInt(phone) and (-1 shl (32 - p))) or 1)
        }

        /** [host] as an IPv4 address when it is written as one; never a DNS lookup. */
        fun literal(host: String): Inet4Address? {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.length > 3 || p.any { !it.isDigit() } || p.toInt() > 255 }) return null
            return InetAddress.getByName(host) as? Inet4Address
        }

        fun toInt(a: Inet4Address): Int {
            val b = a.address
            return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
                ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
        }

        fun toText(ip: Int): String = "${(ip ushr 24) and 0xff}.${(ip ushr 16) and 0xff}.${(ip ushr 8) and 0xff}.${ip and 0xff}"

        internal fun sameSubnet(a: Int, b: Int, prefix: Int): Boolean {
            if (prefix <= 0) return true
            val mask = if (prefix >= 32) -1 else (-1 shl (32 - prefix))
            return (a and mask) == (b and mask)
        }

        /**
         * The addresses to knock on around [phone]: its subnet, but never more than the 254 of the
         * /24 it is in - a /16 home network would be 65 000 knocks, minutes of them. A camera on
         * such a network outside the phone's /24 is found by ONVIF, or entered by hand.
         */
        internal fun sweep(phone: Int, prefix: Int): List<Int> {
            val p = prefix.coerceIn(24, 30)
            val size = 1 shl (32 - p)
            val base = phone and (-1 shl (32 - p))
            val out = ArrayList<Int>(size)
            for (i in 1 until size - 1) {
                val host = base or i
                if (host != phone) out.add(host)
            }
            return out
        }
    }
}
