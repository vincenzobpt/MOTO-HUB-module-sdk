// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
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
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var network: Network? = null
        private set

    /**
     * Joins [ssid] and returns the network once it is up. [onLost] runs if the camera's Wi-Fi goes
     * away later (the camera switched off, out of range). Throws with a reason a rider can read.
     */
    suspend fun join(ssid: String, password: String, onLost: () -> Unit): Network {
        release()
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
                            IllegalStateException(
                                "The phone could not join \"$ssid\". Check that the camera is on, the name and " +
                                    "password are right, and that you allowed the connection when Android asked."
                            )
                        )
                    }
                }
                callback = cb
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
                cont.invokeOnCancellation { release() }
            }
        }
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

    fun release() {
        callback?.let { cb ->
            try { connectivity.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) { }
        }
        callback = null
        network = null
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 60_000
    }
}
