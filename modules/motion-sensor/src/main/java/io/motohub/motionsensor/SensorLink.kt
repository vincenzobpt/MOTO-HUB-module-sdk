// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.motionsensor

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID

/**
 * The Bluetooth side: find the sensor, subscribe to its packet characteristic, send its one-letter
 * commands. Lives only while the diagnostics page is open.
 *
 * Fields are written from Bluetooth threads and read by the page a few times a second, hence
 * @Volatile and no per-packet state objects.
 */
class SensorLink(
    private val context: Context,
    private val calibrationFile: File,
    private val log: (String) -> Unit
) {

    /** Every change is also an event in the app's log, so a tester's report tells the story. */
    @Volatile var status: String = "Not connected"
        private set(value) {
            if (field != value) { field = value; note(value) }
        }
    @Volatile var connected = false
        private set
    @Volatile var deviceLabel: String? = null
        private set
    @Volatile var latest: Packet? = null
        private set
    @Volatile var received = 0L
        private set
    @Volatile var lost = 0L
        private set
    @Volatile var packetsPerSecond = 0
        private set
    @Volatile var stats: String? = null
        private set
    @Volatile var calibration: Calibration? = readCalibration()
        private set

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var lastSeq = -1
    private var windowStart = 0L
    private var windowCount = 0
    private var summaryAt = 0L
    private var lostAtSummary = 0L
    private var lastAlert = false
    private var lastSensorOk = true
    // Calibration: packets between calFrom and calFrom + 1 s are averaged.
    @Volatile private var calFrom = 0L
    private var calCount = 0
    private var calLat = 0.0
    private var calVert = 0.0
    private var calPitch = 0.0
    private val recentLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    /** The last few events, newest first, for the page: a tester can screenshot them. */
    fun recent(): List<String> = synchronized(recentLines) { recentLines.toList() }

    fun note(line: String) {
        log("Motion sensor: $line")
        synchronized(recentLines) {
            recentLines.addFirst("${clock.format(Date())}  $line")
            while (recentLines.size > 8) recentLines.removeLast()
        }
    }

    val hasPermissions: Boolean
        get() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun connect() {
        if (scanning || gatt != null) return
        if (!hasPermissions) { status = "Nearby devices permission missing"; return }
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: run { status = "Bluetooth is off"; return }
        try {
            // No filter: the ESP32 may put its name in the scan response, which filters can miss.
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            scanning = true
            status = "Looking for the sensor…"
            main.postDelayed(scanTimeout, 15_000)
        } catch (e: SecurityException) {
            status = "Nearby devices permission missing"
        }
    }

    fun disconnect() {
        if (gatt != null) note("Disconnecting (received $received, lost $lost)")
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        connected = false
        status = "Not connected"
    }

    /** "C" calibrate, "R" cancel the alert, "Z" reset the statistics, digits = speed in km/h. */
    fun send(command: String): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(SERVICE)?.getCharacteristic(COMMANDS) ?: return false
        val sent = try {
            g.writeCharacteristic(ch, command.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } catch (_: SecurityException) { false }
        note(if (sent) "Sent command $command" else "Command $command could not be sent")
        return sent
    }

    /**
     * The firmware's "C" zeroes the lean and the forward g; this module also measures the box's own
     * tilt so the sideways and vertical g and the pitch start from zero too. The ESP32 stops
     * sending for the second it calibrates, so the averaging starts once it is back.
     */
    fun calibrate() {
        if (!send("C")) return
        calCount = 0
        calLat = 0.0; calVert = 0.0; calPitch = 0.0
        calFrom = System.currentTimeMillis() + 1_500
        status = "Calibrating: keep the bike upright and still…"
        main.postDelayed({ finishCalibration() }, 3_000)
    }

    private fun finishCalibration() {
        if (calFrom == 0L) return
        calFrom = 0L
        if (calCount < 10) {
            status = if (connected) "Connected" else status
            note("Calibration failed: only $calCount packets in the second after the sensor's own")
            return
        }
        val c = Calibration.of((calLat / calCount).toFloat(), (calVert / calCount).toFloat(), (calPitch / calCount).toFloat(), System.currentTimeMillis())
        calibration = c
        writeCalibration(c)
        status = "Connected"
        note(String.format(Locale.ROOT, "Calibrated over %d packets: box rolled %.1f° on its mount, pitch %.1f°",
            calCount, Math.toDegrees(c.rollRad.toDouble()), c.pitchDeg))
    }

    private fun readCalibration(): Calibration? {
        if (!calibrationFile.isFile) return null
        val p = Properties()
        calibrationFile.inputStream().use(p::load)
        val roll = p.getProperty("rollRad")?.toFloatOrNull() ?: return null
        val pitch = p.getProperty("pitchDeg")?.toFloatOrNull() ?: return null
        return Calibration(roll, pitch, p.getProperty("takenAt")?.toLongOrNull() ?: 0L)
    }

    private fun writeCalibration(c: Calibration) {
        val p = Properties()
        p.setProperty("rollRad", c.rollRad.toString())
        p.setProperty("pitchDeg", c.pitchDeg.toString())
        p.setProperty("takenAt", c.takenAt.toString())
        calibrationFile.outputStream().use { p.store(it, null) }
    }

    fun readStats(): Boolean {
        val g = gatt ?: return false
        val ch = g.getService(SERVICE)?.getCharacteristic(STATS) ?: return false
        val asked = try { g.readCharacteristic(ch) } catch (_: SecurityException) { false }
        if (!asked) note("Statistics could not be read")
        return asked
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            status = NOT_FOUND
            note("Nothing named $NAME_PREFIX… in 15 s. The ESP32 only shows itself while no other phone or app (nRF Connect) is connected to it.")
        }
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scan) } catch (_: SecurityException) {}
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: return
            if (!scanning || !name.startsWith(NAME_PREFIX)) return
            stopScan()
            note("Found $name, signal ${result.rssi} dBm")
            main.post { open(result.device, name) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status = "Bluetooth scan failed ($errorCode)"
        }
    }

    private fun open(device: BluetoothDevice, name: String) {
        deviceLabel = "$name · ${device.address}"
        status = "Connecting…"
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            status = "Nearby devices permission missing"
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, gattStatus: Int, newState: Int) {
            try {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    status = "Reading the sensor's services…"
                    // 50 packets a second need the short connection interval.
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else {
                    connected = false
                    g.close()
                    if (gatt === g) gatt = null
                    status = "Disconnected (status $gattStatus)"
                }
            } catch (_: SecurityException) {}
        }

        override fun onServicesDiscovered(g: BluetoothGatt, gattStatus: Int) {
            note("Services read (status $gattStatus): " + g.services.joinToString { it.uuid.toString().take(8) })
            val ch = g.getService(SERVICE)?.getCharacteristic(PACKET)
            if (ch == null) {
                status = "Connected, but no packet characteristic: the firmware is older than V6"
                return
            }
            try {
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD)
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } catch (_: SecurityException) {}
            connected = true
            lastSeq = -1
            summaryAt = System.currentTimeMillis()
            lostAtSummary = lost
            status = "Connected"
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, gattStatus: Int) {
            note(if (gattStatus == BluetoothGatt.GATT_SUCCESS) "Packets switched on" else "Switching packets on failed (status $gattStatus)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid != PACKET) return
            val p = Packet.decode(value) ?: return
            if (lastSeq >= 0) {
                lost += Packet.lostBetween(lastSeq, p.seq)
                if (p.seq < lastSeq && lastSeq - p.seq < 60_000) note("The sensor restarted (counter $lastSeq → ${p.seq})")
            }
            if (p.alert != lastAlert) { lastAlert = p.alert; note(if (p.alert) "FALL ALERT raised by the sensor" else "Fall alert cleared") }
            if (p.sensorOk != lastSensorOk) { lastSensorOk = p.sensorOk; note(if (p.sensorOk) "IMU data back" else "No data from the IMU (wiring?)") }
            lastSeq = p.seq
            received++
            latest = p
            val now0 = System.currentTimeMillis()
            if (calFrom != 0L && now0 >= calFrom && now0 < calFrom + 1_000) {
                calCount++
                calLat += p.latG; calVert += p.vertG; calPitch += p.pitchDeg
            }
            val now = System.currentTimeMillis()
            windowCount++
            if (now - windowStart >= 1000) {
                packetsPerSecond = windowCount
                windowCount = 0
                windowStart = now
            }
            // A heartbeat every 10 s: rate and loss over time, and raw values to check signs.
            if (now - summaryAt >= 10_000) {
                note(String.format(Locale.ROOT, "%d/s, lost %d in 10 s · lean %.1f pitch %.1f · g %.2f/%.2f/%.2f",
                    packetsPerSecond, lost - lostAtSummary, p.leanDeg, p.pitchDeg, p.longG, p.latG, p.vertG))
                summaryAt = now
                lostAtSummary = lost
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, gattStatus: Int) {
            if (ch.uuid != STATS) return
            if (gattStatus == BluetoothGatt.GATT_SUCCESS) {
                stats = String(value)
                note("Statistics: $stats")
            } else note("Statistics read failed (status $gattStatus)")
        }
    }

    companion object {
        const val NAME_PREFIX = "MOTOHUB_"
        const val NOT_FOUND = "Sensor not found"
        val SERVICE: UUID = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214")
        val COMMANDS: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")
        val STATS: UUID = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214")
        val PACKET: UUID = UUID.fromString("19b10003-e8f2-537e-4f6c-d104768a1214")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
