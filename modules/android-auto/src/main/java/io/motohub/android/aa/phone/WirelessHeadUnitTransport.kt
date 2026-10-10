// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa.phone

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import io.motohub.android.aa.AccessoryConnection
import io.motohub.android.aa.SocketAccessoryConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Brings up the wireless link to an external head unit (Carpuride, Chigee and the like) and hands
 * back a connected socket for [io.motohub.android.aa.AapPhoneHandshake] to run AAP over.
 *
 * The chain is Bluetooth RFCOMM -> WPP (the head unit's Wi-Fi credentials) -> join that Wi-Fi ->
 * TCP to the endpoint it named. Each layer where head units are known to differ walks a list of
 * strategies and logs which one worked, so a failure says how far it got.
 */
object WirelessHeadUnitTransport {
    private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")

    private const val TCP_CONNECT_TIMEOUT_MS = 8_000
    private const val WIFI_JOIN_TIMEOUT_MS = 30_000L
    private const val RFCOMM_CHANNEL_MAX = 8
    private const val TCP_RETRIES = 5
    private const val TCP_RETRY_BACKOFF_MS = 1_000L

    class Result(val connection: AccessoryConnection?, val detail: String)

    fun connect(context: Context, targetDevice: BluetoothDevice, log: (String) -> Unit): Result {
        log("=== Wireless head unit transport ===")
        log("Target: ${deviceLabel(targetDevice)}")
        cancelDiscovery(context, log)

        val bt = connectRfcomm(targetDevice, log)
            ?: return Result(null, "RFCOMM failed: none of the connection strategies reached the head unit.")

        try {
            val wpp = WppPhoneHandshake(bt.inputStream, bt.outputStream, log)
            val localIp = getLocalIp()
            log("Local IPv4 offered to head unit: ${localIp ?: "(none found)"}")
            val wppResult = wpp.run(localIp ?: "0.0.0.0")
            if (!wppResult.success || wppResult.endpoint == null || wppResult.credentials == null) {
                return Result(null, "WPP handshake failed: ${wppResult.detail}")
            }
            val endpoint = wppResult.endpoint
            val credentials = wppResult.credentials

            log("--- Wi-Fi join ---")
            val network = joinWifi(context, credentials, log)
            if (network == null) {
                wpp.sendConnectStatus(false)
                return Result(null, "Wi-Fi join failed for ssid=${credentials.ssid}.")
            }
            wpp.sendConnectStatus(true)
            log("Wi-Fi joined. network=$network")

            try { bt.close() } catch (_: Exception) {}

            log("--- TCP to ${endpoint.ipAddress}:${endpoint.port} ---")
            val socket = connectTcp(context, network, endpoint, log)
                ?: return Result(null, "TCP connect to ${endpoint.ipAddress}:${endpoint.port} failed. " +
                    "If the log shows EPERM, an always-on VPN with \"Block connections without VPN\" is blocking it — turn that off and retry.")
            return Result(SocketAccessoryConnection(socket), "Connected to ${endpoint.ipAddress}:${endpoint.port}.")
        } finally {
            try { bt.close() } catch (_: Exception) {}
        }
    }

    // --- RFCOMM ---

    private fun connectRfcomm(device: BluetoothDevice, log: (String) -> Unit): BluetoothSocket? {
        log("--- RFCOMM ---")
        openRfcomm(log, "secure SDP (AA UUID)") { device.createRfcommSocketToServiceRecord(AA_UUID) }?.let { return it }
        openRfcomm(log, "insecure SDP (AA UUID)") { device.createInsecureRfcommSocketToServiceRecord(AA_UUID) }?.let { return it }
        for (channel in 1..RFCOMM_CHANNEL_MAX) {
            openRfcomm(log, "reflection secure channel $channel") {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, channel) as BluetoothSocket
            }?.let { return it }
        }
        for (channel in 1..RFCOMM_CHANNEL_MAX) {
            openRfcomm(log, "reflection insecure channel $channel") {
                val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, channel) as BluetoothSocket
            }?.let { return it }
        }
        return null
    }

    private inline fun openRfcomm(log: (String) -> Unit, label: String, factory: () -> BluetoothSocket): BluetoothSocket? {
        var socket: BluetoothSocket? = null
        return try {
            socket = factory()
            socket.connect()
            log("RFCOMM connected via $label.")
            socket
        } catch (e: Exception) {
            log("RFCOMM $label failed: ${e.javaClass.simpleName}: ${e.message}")
            try { socket?.close() } catch (_: Exception) {}
            null
        }
    }

    // --- Wi-Fi join ---

    private class WifiVariant(val label: String, val applicable: Boolean, val configure: (WifiNetworkSpecifier.Builder) -> Unit)

    private fun joinWifi(context: Context, credentials: WppCredentials, log: (String) -> Unit): Network? {
        val hasKey = credentials.key.isNotEmpty()
        val hasBssid = credentials.bssid.isNotEmpty() && credentials.bssid != "00:00:00:00:00:00"
        val variants = buildList {
            if (hasKey) {
                add(WifiVariant("WPA2 + BSSID", hasBssid) { b -> b.setWpa2Passphrase(credentials.key); pinBssid(b, credentials, hasBssid) })
                add(WifiVariant("WPA2", true) { b -> b.setWpa2Passphrase(credentials.key) })
                add(WifiVariant("WPA3 (SAE)", true) { b -> b.setWpa3Passphrase(credentials.key) })
            } else {
                add(WifiVariant("open + BSSID", hasBssid) { b -> pinBssid(b, credentials, hasBssid) })
                add(WifiVariant("open", true) { _ -> })
            }
        }.filter { it.applicable }

        for (variant in variants) {
            log("Wi-Fi try: ${variant.label} (ssid=${credentials.ssid})")
            val network = joinWifiOnce(context, credentials, variant, log)
            if (network != null) {
                log("Wi-Fi joined via ${variant.label}.")
                return network
            }
        }
        return null
    }

    private fun pinBssid(builder: WifiNetworkSpecifier.Builder, credentials: WppCredentials, hasBssid: Boolean) {
        if (hasBssid) {
            try { builder.setBssid(MacAddress.fromString(credentials.bssid)) } catch (_: Exception) {}
        }
    }

    private fun joinWifiOnce(context: Context, credentials: WppCredentials, variant: WifiVariant, log: (String) -> Unit): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder = WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
        try {
            variant.configure(builder)
        } catch (e: Exception) {
            log("Wi-Fi ${variant.label}: builder rejected: ${e.message}")
            return null
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(builder.build())
            .build()
        val networkRef = AtomicReference<Network?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkRef.set(network); latch.countDown() }
            override fun onUnavailable() { log("Wi-Fi ${variant.label}: onUnavailable"); latch.countDown() }
        }
        cm.requestNetwork(request, callback)
        val joined = try { latch.await(WIFI_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (networkRef.get() == null) {
            if (!joined) log("Wi-Fi ${variant.label}: timed out after ${WIFI_JOIN_TIMEOUT_MS}ms")
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
        return networkRef.get()
    }

    // --- TCP ---

    private fun connectTcp(context: Context, network: Network, endpoint: WppEndpoint, log: (String) -> Unit): Socket? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val address = InetSocketAddress(endpoint.ipAddress, endpoint.port)
        var sawEperm = false
        val strategies: List<Pair<String, () -> Socket>> = listOf(
            "network.socketFactory" to {
                network.socketFactory.createSocket().also { it.connect(address, TCP_CONNECT_TIMEOUT_MS) }
            },
            "bindSocket" to {
                Socket().also { network.bindSocket(it); it.connect(address, TCP_CONNECT_TIMEOUT_MS) }
            },
            "bindProcessToNetwork" to {
                val previous = cm.boundNetworkForProcess
                cm.bindProcessToNetwork(network)
                try {
                    Socket().also { it.connect(address, TCP_CONNECT_TIMEOUT_MS) }
                } finally {
                    try { cm.bindProcessToNetwork(previous) } catch (_: Exception) {}
                }
            }
        )
        for (attempt in 1..TCP_RETRIES) {
            for ((label, open) in strategies) {
                try {
                    val socket = open()
                    log("TCP connected to ${endpoint.ipAddress}:${endpoint.port} via $label (attempt $attempt).")
                    return socket
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    if (msg.contains("EPERM")) sawEperm = true
                    log("TCP $label attempt $attempt/$TCP_RETRIES failed: $msg")
                }
            }
            try { Thread.sleep(TCP_RETRY_BACKOFF_MS) } catch (_: InterruptedException) {}
        }
        if (sawEperm) {
            log("All TCP binds were refused with EPERM. This is an always-on VPN set to " +
                "\"Block connections without VPN\": turn it off (Settings -> Network -> VPN) and run again.")
        }
        return null
    }

    // --- Utility ---

    private fun cancelDiscovery(context: Context, log: (String) -> Unit) {
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
                log("Cancelled Bluetooth discovery (it slows RFCOMM).")
            }
        } catch (e: Exception) {
            log("Could not cancel discovery: ${e.message}")
        }
    }

    private fun deviceLabel(device: BluetoothDevice): String = try {
        "${device.name ?: "?"} (${device.address})"
    } catch (_: SecurityException) {
        device.address
    }

    private fun getLocalIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
