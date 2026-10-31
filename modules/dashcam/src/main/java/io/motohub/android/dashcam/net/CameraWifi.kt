// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import java.net.Inet4Address
import java.net.Socket
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The camera's own access point, joined for as long as the module needs it.
 *
 * A WifiNetworkSpecifier request: the system asks the rider once to allow it, then joins without
 * asking again, and treats the network as local-only - no internet, never the phone's default -
 * so nothing else on the phone wanders onto it and the camera's lack of internet does not get it
 * dropped.
 *
 * On many phones (the OnePlus this was built on included: config_wifiMultiStaLocalOnlyConcurrency
 * is false) the request takes the phone's only Wi-Fi connection. That is why the module refuses
 * to run while the phone is connected to the motorcycle: joining the camera would cut the bike off.
 */
class CameraWifi(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var network: Network? = null
        private set

    /**
     * True when [network] is a Wi-Fi the rider joined in Android's settings: the module only uses
     * it, and letting go of it leaves the phone on it.
     */
    @Volatile
    var borrowed: Boolean = false
        private set

    /**
     * Joins [ssid] and returns the network once it is up. [onLost] runs if the camera's Wi-Fi goes
     * away later (the camera switched off, out of range). Throws with a reason a rider can read.
     */
    suspend fun join(ssid: String, password: String, onLost: () -> Unit): Network {
        release()
        // Already on it, joined in Android's settings: asking again fails on some phones (a Samsung
        // on Android 16 did, 20 times in a row), and there is nothing to join.
        connectedTo(ssid)?.let { return watch(it, onLost) }
        val started = SystemClock.elapsedRealtime()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        return withTimeout(JOIN_TIMEOUT_MS + 5_000L) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) {
                        network = n
                        if (cont.isActive) cont.resume(n)
                    }

                    override fun onLost(n: Network) {
                        if (n == network) {
                            network = null
                            onLost()
                        }
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resumeWithException(
                            IllegalStateException(joinFailure(ssid, (SystemClock.elapsedRealtime() - started) / 1000))
                        )
                    }
                }
                synchronized(this@CameraWifi) { callback = cb }
                try {
                    connectivity.requestNetwork(request, cb, JOIN_TIMEOUT_MS)
                } catch (e: SecurityException) {
                    callback = null
                    cont.resumeWithException(
                        IllegalStateException(
                            "MOTO-HUB is not allowed to join Wi-Fi networks. Allow \"Nearby devices\" for MOTO-HUB " +
                                "in Android's settings, then try again."
                        )
                    )
                }
                cont.invokeOnCancellation { release(cb) }
            }
        }
    }

    /**
     * The Wi-Fi network the phone is on now, when it is [ssid]. Null when it is not, or when the
     * app may not read the name (no location permission: Android then says "<unknown ssid>").
     */
    @Suppress("DEPRECATION") // connectionInfo: the one read that names the joined Wi-Fi without a callback.
    private fun connectedTo(ssid: String): Network? {
        val name = runCatching { wifiManager.connectionInfo?.ssid }.getOrNull() ?: return null
        val bare = if (name.length >= 2 && name.startsWith('"') && name.endsWith('"')) name.substring(1, name.length - 1) else name
        if (bare != ssid) return null
        // shortcut: the first Wi-Fi network is taken as that one; a phone on two Wi-Fi at once is not told apart.
        return connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** Uses [n], joined by the rider, and only listens for it to go away: releasing it disconnects nothing. */
    private fun watch(n: Network, onLost: () -> Unit): Network {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (lost == network) {
                    network = null
                    onLost()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        synchronized(this) {
            callback = cb
            network = n
            borrowed = true
        }
        connectivity.registerNetworkCallback(request, cb)
        return n
    }

    /**
     * Why the join failed, as far as Android lets anyone tell: it says only "unavailable", so the
     * time it took and whether the camera's Wi-Fi is in the phone's last scan are what is left.
     */
    private fun joinFailure(ssid: String, seconds: Long): String {
        val why = if (seconds < JOIN_TIMEOUT_MS / 1000 - 5) {
            "Android gave up after $seconds s - the connection was declined when Android asked, or the phone does not see the camera."
        } else {
            "Android found no way onto it in ${JOIN_TIMEOUT_MS / 1000} s - the password may be wrong."
        }
        @Suppress("DEPRECATION") // ScanResult.SSID: wifiSsid is API 33, the module runs on older phones too.
        val seen = runCatching { wifiManager.scanResults.any { it.SSID == ssid } }.getOrNull()
        val nearby = when (seen) {
            true -> " \"$ssid\" is in range."
            false -> " The phone's last Wi-Fi scan does not list \"$ssid\"."
            null -> ""
        }
        return "The phone could not join \"$ssid\": $why$nearby Check that the camera is on and the name and password are " +
            "right - or join \"$ssid\" in Android's Wi-Fi settings and try again."
    }

    /** Binds a socket to the camera's network, so it never leaves through mobile data. */
    fun bind(socket: Socket) {
        val n = network ?: throw IllegalStateException("The camera's Wi-Fi is not connected.")
        n.bindSocket(socket)
    }

    /** Where the camera is: the gateway of its own network, which is the camera itself. */
    fun gateway(): String? {
        val n = network ?: return null
        val lp = connectivity.getLinkProperties(n) ?: return null
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress?.let { return it }
        return lp.dhcpServerAddress?.hostAddress
    }

    /** The phone's own address on the camera's network; some cameras register the client by it. */
    fun phoneAddress(): String? {
        val n = network ?: return null
        return connectivity.getLinkProperties(n)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    }

    /** The join in place now: hand it to [release] later to let go of that one and no other. */
    val current: ConnectivityManager.NetworkCallback? get() = callback

    /**
     * Lets go of [only], by default whatever is joined now. If a later join has replaced it, nothing
     * happens: a disconnect finishing late must not drop the connection made after it.
     */
    @Synchronized
    fun release(only: ConnectivityManager.NetworkCallback? = callback) {
        if (only !== callback) return
        callback?.let { cb ->
            try { connectivity.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) { }
        }
        callback = null
        network = null
        borrowed = false
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 60_000
    }
}
