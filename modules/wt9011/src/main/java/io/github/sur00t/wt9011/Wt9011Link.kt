// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.github.sur00t.wt9011

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.motohub.android.module.ModuleLogSink
import io.motohub.android.module.ModuleMotionListener
import io.motohub.android.module.ModuleMotionSample
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * The Bluetooth link to a WitMotion WT9011DCL, as a GATT **central**: the module is the one that
 * scans, connects and subscribes, so the sensor talks to the phone directly and no bridge board sits
 * in between.
 *
 * The protocol has no length byte, no checksum and no sequence number, and the device streams as
 * soon as notifications are enabled - there is nothing to ask it to start. What that
 * means for this class:
 *
 * - Every notification is fed straight into [Wt9011FrameParser], which owns resynchronisation. One
 *   notification is not one frame, and a chunk can carry the tail of one frame and the head of the
 *   next.
 * - A sample is "live" because a frame arrived, not because anything was acknowledged. The watchdog
 *   below is the only health signal: nought bytes for two seconds is a dead link, and it is torn
 *   down and rescanned rather than left on screen looking connected.
 * - Register reads (`FF AA 27 <REG> 00`) come back on the *same* characteristic as the stream, so
 *   replies are matched by flag and echoed register, and retried a few times, never assumed.
 *
 * Configuration is flash-backed on the device and costs it about a second of silence, so the
 * set-up block runs once per install - and again when the flat/on-edge mounting direction changes -
 * and is verified afterwards instead of being repeated on every connection. Per connection the only
 * mandatory step is reading the output-content register, and even that is only repaired when it
 * says something other than accelerometer + gyro + angle.
 *
 * Every sample, with the rider's zero and mount applied, goes to MOTO-HUB through the motion
 * listeners (contract 43): the app records it in the trip and draws it on the Ride Dashboard in
 * place of the phone's own sensors. Nothing is kept on the phone by this module.
 *
 * The module only ever talks to one sensor: the first WT sensor it finds at startup is remembered
 * by its address, and from then on nothing else is touched - not another rider's WitMotion at a
 * meeting, not anything else whose name starts with WT. The search starts as soon as the module is
 * created; a manual Disconnect stops the current session only.
 *
 * Everything below the public surface runs on the main looper; the GATT callbacks arrive on a
 * binder thread and reach the state through `@Volatile` fields, which is what the screen reads.
 */
class Wt9011Link(
    private val context: Context,
    private val storage: File,
    private val log: ModuleLogSink
) {
    private val main = Handler(Looper.getMainLooper())
    private val zeroFile = File(storage, "zero.properties")
    private val mountingFile = File(storage, "mounting.properties")
    private val setupFile = File(storage, "setup.properties")

    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var connecting = false
    /** The module is unloading; scheduled retries must not reopen the link. */
    @Volatile private var released = false
    private var scanAttempts = 0
    private var lastReconnectAt = 0L
    private var subscribedAt = 0L
    private var outputContentFixes = 0
    private var setupInFlight = false

    /** Only ever touched from the GATT callback thread after a connection is up. */
    private val parser = Wt9011FrameParser()
    private var rateWindowStart = 0L
    private var rateWindowCount = 0
    private var lowGSince = 0L

    @Volatile private var awaitingRegister = -1
    @Volatile private var awaitingTries = 0

    /** The screen's state. Written from whichever thread owns the event, read by the composable. */
    @Volatile var status: String = "Idle"
        private set
    @Volatile var connected: Boolean = false
        private set
    @Volatile var deviceLabel: String? = null
        private set
    @Volatile var latest: Packet? = null
        private set
    @Volatile var raw: Wt9011Raw? = null
        private set
    @Volatile var zero: Zero? = null
        private set
    @Volatile var mountPosition: MountPosition = MountPosition.DEFAULT
        private set
    @Volatile var packetsPerSecond: Int = 0
        private set
    @Volatile var received: Long = 0L
        private set
    @Volatile var lastFrameAt: Long = 0L
        private set
    @Volatile var batteryPercent: Int? = null
        private set
    @Volatile var batteryRaw: Int? = null
        private set
    @Volatile var temperatureC: Float? = null
        private set
    @Volatile var outputContent: Int? = null
        private set
    @Volatile var configured: Boolean = false
        private set
    @Volatile var displacementSuspected: Boolean = false
        private set
    @Volatile var autoConnect: Boolean = true
        private set

    /** The one sensor this module talks to, once the rider has connected it. Null before that. */
    @Volatile var pinnedAddress: String? = null
        private set

    /** Devices named WT… that turned out not to be the IMU, so a scan does not keep opening them. */
    private val rejected = HashSet<String>()

    private val motionListeners = CopyOnWriteArrayList<ModuleMotionListener>()

    /** Maxima over every sample, not just the screen's 10 Hz polls. */
    private val peaks = Peaks()

    private val recent = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    init {
        // Files first: the screen may be drawn before the first frame arrives, and the rider's
        // mount has to be right at that point.
        loadStoredState()
    }

    // ---------------------------------------------------------------------------------------
    // What the screen reads
    // ---------------------------------------------------------------------------------------

    /** Everything the diagnostics screen draws, read in one go so a frame cannot half-update it. */
    fun snapshot(): Wt9011Snapshot {
        val ride = rideOf(latest ?: return emptySnapshot())
        val age = when {
            lastFrameAt > 0L -> SystemClock.elapsedRealtime() - lastFrameAt
            subscribedAt > 0L -> SystemClock.elapsedRealtime() - subscribedAt
            else -> -1L
        }
        return Wt9011Snapshot(
            status = status,
            connected = connected,
            deviceLabel = deviceLabel,
            raw = raw,
            ride = ride,
            peaks = peaks.copyOf(),
            packetsPerSecond = packetsPerSecond,
            frameAgeMs = age,
            batteryPercent = batteryPercent,
            batteryRaw = batteryRaw,
            temperatureC = temperatureC,
            outputContent = outputContent,
            zero = zero,
            mountPosition = mountPosition,
            configured = configured,
            displacementSuspected = displacementSuspected,
            autoConnect = autoConnect,
            received = received,
            recent = recentLines(),
            pinnedAddress = pinnedAddress
        )
    }

    /** What the rider sees and MOTO-HUB records: the mount first, then the zero in that frame. */
    private fun rideOf(packet: Packet): Packet =
        zero?.apply(mountPosition.apply(packet)) ?: mountPosition.apply(packet)

    private fun emptySnapshot() = Wt9011Snapshot(
        status = status,
        connected = connected,
        deviceLabel = deviceLabel,
        raw = raw,
        ride = null,
        peaks = Peaks(),
        packetsPerSecond = packetsPerSecond,
        frameAgeMs = -1L,
        batteryPercent = batteryPercent,
        batteryRaw = batteryRaw,
        temperatureC = temperatureC,
        outputContent = outputContent,
        zero = zero,
        mountPosition = mountPosition,
        configured = configured,
        displacementSuspected = displacementSuspected,
        autoConnect = autoConnect,
        received = received,
        recent = recentLines(),
        pinnedAddress = pinnedAddress
    )

    /** Recent notable events, oldest first. Not the frame stream - that is 20 lines a second. */
    fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

    // ---------------------------------------------------------------------------------------
    // Rider actions
    // ---------------------------------------------------------------------------------------

    /** MOTO-HUB's listeners for every sample (contract 43). Called from any thread. */
    fun addMotionListener(listener: ModuleMotionListener) {
        motionListeners.addIfAbsent(listener)
    }

    fun removeMotionListener(listener: ModuleMotionListener) {
        motionListeners.remove(listener)
    }

    /** Connects on purpose: the rider asked, so a later drop should reconnect by itself. */
    fun connect() {
        autoConnect = true
        saveSetup()
        main.post {
            if (!connected && !scanning && !connecting) {
                scanAttempts = 0
                beginScan()
            }
        }
    }

    /**
     * Called when the module loads. Reconnects only to the sensor the rider already connected, and
     * only while [autoConnect] is on. Never pins a new sensor and never saves anything.
     *
     * MOTO-HUB-FIX 2026-11-22 (Vincenzo Buonomano, MOTO-HUB maintainer), fix 1 of 4: replaces the automatic `connect()` at load.
     * AI-NOTE: keep this behaviour when you regenerate or refactor this code; see docs/MAINTAINER_FIXES.md.
     */
    fun resume() {
        if (pinnedAddress == null) {
            status = "Turn the sensor on, then tap Connect."
            return
        }
        if (!autoConnect) {
            status = "Disconnected. Tap Connect to use the sensor."
            return
        }
        if (!connected && !scanning && !connecting) {
            scanAttempts = 0
            beginScan()
        }
    }

    /** Drops the link and looks for the sensor again straight away. */
    fun reconnect() {
        autoConnect = true
        saveSetup()
        main.post {
            shutDown("Reconnecting…")
            scanAttempts = 0
            beginScan()
        }
    }

    /**
     * Lets go of the remembered sensor, for a replaced or borrowed unit: the pin, zero and peaks
     * are cleared. The module does NOT scan again by itself: the rider taps Connect for the new one.
     *
     * MOTO-HUB-FIX 2026-11-22 (Vincenzo Buonomano, MOTO-HUB maintainer), fix 1 of 4: forgetting used to start a scan that pinned the first WT sensor in range.
     * AI-NOTE: keep this behaviour when you regenerate or refactor this code; see docs/MAINTAINER_FIXES.md.
     */
    fun forgetSensor(): String {
        pinnedAddress = null
        configured = false
        autoConnect = false
        // The zero belongs to the unit that was fitted; the bracket and its mount do not.
        zero = null
        zeroFile.delete()
        peaks.reset()
        saveSetup()
        main.post {
            rejected.clear()
            shutDown("Sensor forgotten. Turn the new one on, then tap Connect.")
        }
        note("sensor forgotten; zero cleared")
        return "Sensor forgotten and zero cleared. Turn the new sensor on, then tap Connect."
    }

    /** Stops the link and the automatic reconnect with it. */
    fun disconnect() {
        autoConnect = false
        saveSetup()
        main.post { shutDown("Disconnected") }
    }

    /** Captures the current attitude as zero. Returns a message for the screen either way. */
    fun captureZero(): String {
        if (!connected) return "No link - connect the sensor first."
        val packet = latest ?: return "No sample yet - is the sensor streaming?"
        val age = if (lastFrameAt > 0L) SystemClock.elapsedRealtime() - lastFrameAt else -1L
        if (age < 0L || age > ZERO_MAX_AGE_MS) {
            return "No fresh sample - wait for the sensor to stream before zeroing."
        }
        val mounted = mountPosition.apply(packet)
        val refusal = zeroRefusal(mounted)
        if (refusal != null) return refusal
        val captured = Zero.of(mounted, System.currentTimeMillis())
            ?: return "That sample cannot be a zero - try again when the bike is still."
        zero = captured
        saveZero(captured)
        peaks.reset()
        note("zero captured: lean %.2f°, pitch %.2f°".format(Locale.ROOT, captured.leanDeg, captured.pitchDeg))
        return String.format(
            Locale.ROOT,
            "Zeroed at lean %.1f°, pitch %.1f°.",
            captured.leanDeg,
            captured.pitchDeg
        )
    }

    fun clearZero(): String {
        zero = null
        zeroFile.delete()
        peaks.reset()
        note("zero cleared")
        return "Zero cleared - readings are the sensor's own again."
    }

    /** Clears the directional peaks; the screen's Reset peaks button. */
    fun resetPeaks(): String {
        peaks.reset()
        note("peaks reset")
        return "Peaks reset."
    }

    /**
     * The rider's mount: which way the sensor points, which face is outward, and whether it lies
     * flat or stands on its edge. Persisted, because it is a fitting decision, not a session one.
     *
     * A captured zero belongs to the frame the mount defines, so changing the mount throws it
     * away. A change between flat and on-edge also changes the sensor's own installation direction,
     * which is a flash-backed setting: the module rewrites it now when the link is up, or on the
     * next connection otherwise.
     */
    fun setMountPosition(position: MountPosition): MountChange {
        if (position == mountPosition) return MountChange(zeroCleared = false, directionChanged = false)
        val directionChanged = position.plane != mountPosition.plane
        mountPosition = position
        saveMounting()
        val cleared = zero != null
        if (cleared) {
            zero = null
            zeroFile.delete()
            note("mount changed; zero cleared")
        }
        note("mount: ${position.label.lowercase(Locale.ROOT)}")
        peaks.reset()
        if (directionChanged) {
            if (connected) {
                note("installation direction -> ${position.plane.name.lowercase(Locale.ROOT)}; rewriting the settings")
                runSetupBlock { readOutputContent() }
            } else {
                // The next connection sees the flag and runs the whole block with the new direction.
                configured = false
                saveSetup()
                note("installation direction -> ${position.plane.name.lowercase(Locale.ROOT)}; it is written on the next connection")
            }
        }
        return MountChange(zeroCleared = cleared, directionChanged = directionChanged)
    }

    /**
     * Re-sends the whole set-up block on demand, for the bench and for a sensor that was configured
     * for something else. It is not part of connecting: the device writes this to its own flash.
     */
    fun configureSensor(): String {
        if (!connected) return "Connect first - configuration needs a live link."
        outputContentFixes = 0
        note("set-up requested by hand")
        runSetupBlock { readOutputContent() }
        return "Set-up sent. The sensor stops streaming for about a second."
    }

    /** Asks for battery and temperature now instead of waiting for the periodic poll. */
    fun refreshDiagnostics() {
        if (!connected) return
        readRegister(Wt9011Protocol.REG_BATTERY)
        main.postDelayed({ readRegister(Wt9011Protocol.REG_TEMP) }, 400)
    }

    /**
     * Lets go of the sensor because the app is unloading this module - not because the rider asked.
     *
     * Deliberately does not touch [autoConnect]: a rider who wants lean on the Ride page tomorrow
     * should not have to remember to tap Connect after every app update. [disconnect] is the one
     * that records a decision.
     */
    fun release() {
        released = true
        motionListeners.clear()
        main.post { shutDown("Idle") }
    }

    // ---------------------------------------------------------------------------------------
    // Scanning and connecting
    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun beginScan() {
        if (released || scanning || connecting || connected) return
        val adapter = adapter()
        if (adapter == null) {
            status = "This phone reports no Bluetooth adapter."
            retryWhenReady()
            return
        }
        if (!hasPermissions()) {
            status = "Bluetooth permission is not granted to MOTO-HUB."
            retryWhenReady()
            return
        }
        if (!adapter.isEnabled) {
            status = "Bluetooth is off."
            retryWhenReady()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            status = "This phone has no BLE scanner."
            retryWhenReady()
            return
        }
        // Fast while the sensor has only just gone; gentler once it has been away a while.
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (scanAttempts < MAX_SCAN_ATTEMPTS) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED
            )
            .build()
        // MOTO-HUB-FIX 2026-11-22 (Vincenzo Buonomano, MOTO-HUB maintainer), fix 3 of 4.
        // Once a sensor is pinned, scan for its address only. Android pauses UNFILTERED scans while
        // the screen is off, so with the phone in a pocket a dropped sensor never came back.
        // Before the first pin there is still no filter (see the comment below).
        // AI-NOTE: keep this behaviour when you regenerate or refactor this code; see docs/MAINTAINER_FIXES.md.
        val filters = pinnedAddress?.let { listOf(ScanFilter.Builder().setDeviceAddress(it).build()) }
        try {
            // No name filter at scan time on purpose: a ScanFilter on the advertised name would
            // hide a unit whose advertisement is minimal, and the name is checked below anyway.
            scanner.startScan(filters, settings, scanCallback)
        } catch (_: SecurityException) {
            status = "Bluetooth permission was refused."
            retryWhenReady()
            return
        }
        scanning = true
        scanAttempts++
        status = if (scanAttempts <= MAX_SCAN_ATTEMPTS) {
            "Searching for ${if (pinnedAddress != null) "the sensor" else "a WT sensor"} (attempt $scanAttempts of $MAX_SCAN_ATTEMPTS)…"
        } else {
            "Searching for the sensor…"
        }
        note("scan started")
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private val scanTimeout = Runnable {
        if (!scanning) return@Runnable
        stopScan()
        status = "No WT sensor answered."
        note("scan timed out")
        if (autoConnect) {
            // Never give up while the rider wants the sensor: it may be back in a minute, in the
            // middle of a ride. After the first attempts the gaps get longer.
            val delay = if (scanAttempts < MAX_SCAN_ATTEMPTS) RECONNECT_DELAY_MS else IDLE_RETRY_MS
            if (scanAttempts >= MAX_SCAN_ATTEMPTS) status = "Sensor not in range. Looking again every ${IDLE_RETRY_MS / 1000} s."
            main.postDelayed({
                if (autoConnect && !connected && !scanning && !connecting) beginScan()
            }, delay)
        } else {
            status = "No WT sensor found. Turn it on, then tap Connect."
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanning = false
        main.removeCallbacks(scanTimeout)
        val adapter = adapter() ?: return
        try {
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // Nothing to undo if the permission went away mid-scan.
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // MOTO-HUB-FIX 2026-11-22 (Vincenzo Buonomano, MOTO-HUB maintainer), fix 4 of 4.
            // A result already queued when stopScan(), disconnect() or release() ran must not open
            // a link: it reconnected after Disconnect, or left a GATT open after the module unloaded.
            // AI-NOTE: keep this behaviour when you regenerate or refactor this code; see docs/MAINTAINER_FIXES.md.
            if (released || !scanning) return
            val address = result.device.address
            val pinned = pinnedAddress
            if (pinned != null) {
                if (address != pinned) return
            } else if (address in rejected) {
                return
            }
            val name = advertisementName(result)
                ?: if (pinned != null) "the sensor" else return
            if (pinned == null && !name.startsWith(Wt9011Protocol.NAME_PREFIX)) return
            if (connecting || connected) return
            stopScan()
            connecting = true
            deviceLabel = name
            main.post { openGatt(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                main.removeCallbacks(scanTimeout)
                status = "Scan failed (code $errorCode)."
                note("scan failed: $errorCode")
                retryWhenReady()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun advertisementName(result: ScanResult): String? =
        result.scanRecord?.deviceName
            ?: try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice) {
        // MOTO-HUB-FIX 2026-11-22 (Vincenzo Buonomano, MOTO-HUB maintainer), fix 4 of 4: the module may have been unloaded while this was queued.
        if (released) {
            connecting = false
            return
        }
        stopScan()
        connecting = true
        status = "Connecting to ${deviceLabel ?: "the sensor"}…"
        val opened = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            null
        }
        if (opened == null) {
            connecting = false
            status = "Could not open the Bluetooth link."
            return
        }
        gatt = opened
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (!connected && connecting) {
            note("connect timed out")
            shutDown("Connecting timed out.")
            retryLater()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { onConnected(gatt) }
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                main.post { onLinkLost(gatt, status) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post { onServices(gatt, status) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != Wt9011Protocol.CCCD) return
            main.post { onSubscribed(gatt, status) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val uuid = characteristic.uuid
            if (!Wt9011Protocol.matchesShort(uuid, Wt9011Protocol.DATA) &&
                !Wt9011Protocol.matchesShort(uuid, Wt9011Protocol.FALLBACK_DATA)
            ) {
                return
            }
            onBytes(value, SystemClock.elapsedRealtimeNanos())
        }
    }

    @SuppressLint("MissingPermission")
    private fun onConnected(gatt: BluetoothGatt) {
        main.removeCallbacks(connectTimeout)
        if (gatt !== this.gatt) {
            // A callback from a link that was already abandoned.
            gatt.close()
            return
        }
        // MOTO-HUB-FIX 2026-11-22 (Vincenzo Buonomano, MOTO-HUB maintainer), fix 2 of 4.
        // The timeout is armed again for service discovery and the notification subscription; it
        // is cleared in onSubscribed. Without it a sensor that went quiet half-way left the module
        // on "Discovering services…" forever: every automatic retry waits for !connecting.
        // AI-NOTE: keep this behaviour when you regenerate or refactor this code; see docs/MAINTAINER_FIXES.md.
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        status = "Discovering services…"
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (_: SecurityException) {
            // Priority is a hint; the link works without it.
        }
        val started = try {
            gatt.discoverServices()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            shutDown("Service discovery would not start.")
            retryLater()
        }
    }

    @SuppressLint("MissingPermission")
    private fun onServices(gatt: BluetoothGatt, status: Int) {
        if (gatt !== this.gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            shutDown("Service discovery failed (status $status).")
            retryLater()
            return
        }
        // Requiring the documented service is deliberate: the track has other WitMotion devices,
        // and a wrong choice here would decode nonsense frames instead of failing.
        val services = gatt.services
        val documented = services.firstOrNull {
            Wt9011Protocol.matchesShort(it.uuid, Wt9011Protocol.SERVICE)
        }
        val fallback = services.firstOrNull {
            Wt9011Protocol.matchesShort(it.uuid, Wt9011Protocol.FALLBACK_SERVICE)
        }
        val service = documented ?: fallback
        if (service == null) {
            note("no ffe5 and no fallback service on this device")
            rejected += gatt.device.address
            shutDown("That device has no WT sensor service - it is not the IMU.")
            retryLater()
            return
        }
        // Characteristics are looked up on the service that was found: the fallback service does not
        // carry the documented ffe5 characteristics, so the pair has to come from the same service.
        val data: BluetoothGattCharacteristic?
        val command: BluetoothGattCharacteristic?
        if (documented != null) {
            data = documented.characteristics.firstOrNull {
                Wt9011Protocol.matchesShort(it.uuid, Wt9011Protocol.DATA)
            }
            command = documented.characteristics.firstOrNull {
                Wt9011Protocol.matchesShort(it.uuid, Wt9011Protocol.COMMAND)
            }
        } else {
            data = service.getCharacteristic(Wt9011Protocol.FALLBACK_DATA)
            command = service.getCharacteristic(Wt9011Protocol.FALLBACK_COMMAND)
        }
        if (data == null || command == null) {
            shutDown("The WT service is missing its data or command characteristic.")
            retryLater()
            return
        }
        commandCharacteristic = command
        val cccd = data.getDescriptor(Wt9011Protocol.CCCD)
        if (cccd == null) {
            shutDown("The data characteristic has no notification descriptor.")
            retryLater()
            return
        }
        val subscribed = try {
            gatt.setCharacteristicNotification(data, true) &&
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } catch (_: SecurityException) {
            false
        }
        if (!subscribed) {
            shutDown("Could not subscribe to the data characteristic.")
            retryLater()
            return
        }
        this.status = "Subscribing…"
    }

    @SuppressLint("MissingPermission")
    private fun onSubscribed(gatt: BluetoothGatt, status: Int) {
        if (gatt !== this.gatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            shutDown("The sensor refused the notification subscription ($status).")
            retryLater()
            return
        }
        // MOTO-HUB-FIX 2026-11-22, fix 2 of 4: subscribed, so the connect timeout is done.
        main.removeCallbacks(connectTimeout)
        connected = true
        connecting = false
        // Notifications have only just been switched on, so nothing is mid-frame yet.
        parser.reset()
        if (pinnedAddress == null) {
            pinnedAddress = gatt.device.address
            saveSetup()
            note("sensor ${gatt.device.address} remembered; only this one is used from now on")
        }
        subscribedAt = SystemClock.elapsedRealtime()
        lastFrameAt = 0L
        scanAttempts = 0
        outputContentFixes = 0
        this.status = "Listening"
        note("subscribed; the sensor streams on its own from here")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        // A device that was never configured here needs the set-up block; one that was only needs
        // its output content checked, because everything else stays in its flash.
        if (configured) {
            readOutputContent()
            scheduleDiagnostics()
            requestMtuSoon()
        } else {
            runSetupBlock {
                readOutputContent()
                requestMtuSoon()
            }
        }
    }

    /**
     * Asks for a larger ATT MTU after the setup block and the first register read have gone out.
     * Requesting it before discoverServices() suppresses onServicesDiscovered on some Android
     * stacks, and issuing it alongside the setup writes can make the first write fail.
     */
    @SuppressLint("MissingPermission")
    private fun requestMtuSoon() {
        main.postDelayed({
            val current = gatt ?: return@postDelayed
            if (!connected) return@postDelayed
            try {
                current.requestMtu(247)
            } catch (_: SecurityException) {
                // The link works with the default MTU; it is only slower at high rates.
            }
        }, MTU_DELAY_MS)
    }

    private fun onLinkLost(gatt: BluetoothGatt?, status: Int) {
        if (gatt != null && this.gatt != null && gatt !== this.gatt) return
        val wasConnected = connected
        shutDown(if (wasConnected) "Link lost (status $status)." else "Could not connect.")
        if (wasConnected) note("link lost, status $status")
        retryLater()
    }

    @SuppressLint("MissingPermission")
    private fun shutDown(message: String) {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(diagnosticsPoll)
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(scanTimeout)
        stopScan()
        connected = false
        connecting = false
        subscribedAt = 0L
        awaitingRegister = -1
        packetsPerSecond = 0
        lowGSince = 0L
        displacementSuspected = false
        commandCharacteristic = null
        val previous = gatt
        gatt = null
        if (previous != null) {
            try {
                previous.disconnect()
                previous.close()
            } catch (_: SecurityException) {
                // The stack will drop it when the process lets go of the proxy.
            }
        }
        status = message
    }

    /** If the adapter/permission is not ready yet, keep trying while the rider wants a link. */
    private fun retryWhenReady() {
        if (released || !autoConnect) return
        main.postDelayed({
            if (autoConnect && !connected && !scanning && !connecting) beginScan()
        }, IDLE_RETRY_MS)
    }

    /** One automatic retry window after a drop, so a flaky link does not spin forever. */
    private fun retryLater() {
        if (released || !autoConnect) return
        val now = SystemClock.elapsedRealtime()
        val wait = (RECONNECT_DELAY_MS - (now - lastReconnectAt)).coerceAtLeast(0L)
        lastReconnectAt = now + wait
        main.postDelayed({
            if (autoConnect && !connected && !scanning && !connecting) beginScan()
        }, wait)
    }

    // ---------------------------------------------------------------------------------------
    // The frame stream
    // ---------------------------------------------------------------------------------------

    private fun onBytes(value: ByteArray, nowNanos: Long) {
        val now = nowNanos / 1_000_000L
        for (frame in parser.feed(value, now)) {
            when (frame) {
                is Wt9011Frame.Sample -> onSample(frame.sample, now, nowNanos)
                is Wt9011Frame.Registers -> onRegisterReply(frame.reply)
            }
        }
    }

    private fun onSample(sample: Wt9011Sample, now: Long, nowNanos: Long) {
        latest = sample.packet
        raw = sample.raw
        lastFrameAt = now
        received++
        rateWindowCount++
        if (rateWindowStart == 0L) {
            rateWindowStart = now
        } else if (now - rateWindowStart >= 1000L) {
            packetsPerSecond = (rateWindowCount * 1000L / (now - rateWindowStart)).toInt()
            rateWindowStart = now
            rateWindowCount = 0
        }
        // Displacement mode reports no gravity at all, so every axis sits near zero: with the bike
        // at rest one axis must read about 1 g. Two seconds of that is displacement mode.
        val p = sample.packet
        if (abs(p.longG) < 0.5f && abs(p.latG) < 0.5f && abs(p.vertG) < 0.5f) {
            if (lowGSince == 0L) lowGSince = now
            if (now - lowGSince > DISPLACEMENT_MS && !displacementSuspected) {
                displacementSuspected = true
                note("no gravity on any axis: output content is probably not accel+gyro+angle")
            }
        } else {
            lowGSince = 0L
            // If gravity comes back (a repaired output-content register, or the unit simply
            // stopped sending displacement), stop warning.
            displacementSuspected = false
        }
        val ride = rideOf(p)
        peaks.add(ride)
        if (motionListeners.isNotEmpty()) publish(ride, nowNanos)
    }

    /** Hands one sample to MOTO-HUB in the contract's frame: x forward, y left, z up. */
    private fun publish(ride: Packet, nowNanos: Long) {
        val sample = ModuleMotionSample(
            elapsedRealtimeNanos = nowNanos,
            leanDegrees = ride.leanDeg,
            pitchDegrees = ride.pitchDeg,
            forwardG = ride.longG,
            leftG = -ride.latG,
            upG = ride.vertG,
            rollRateRadiansPerSecond = ride.rollRate,
            pitchRateRadiansPerSecond = ride.pitchRate,
            yawRateRadiansPerSecond = ride.yawRate,
            magneticHeadingDegrees = bearingDeg(ride.yawDeg),
            zeroed = zero != null
        )
        for (listener in motionListeners) {
            try {
                listener.onMotion(sample)
            } catch (_: Throwable) {
                // The app's problem, not a reason to drop the Bluetooth thread.
            }
        }
    }

    private fun onRegisterReply(reply: RegisterReply) {
        reply.short(Wt9011Protocol.REG_BATTERY)?.let {
            batteryRaw = it
            batteryPercent = batteryPercentOf(it)
        }
        reply.short(Wt9011Protocol.REG_TEMP)?.let { temperatureC = it / 100f }
        reply.short(Wt9011Protocol.REG_AGPVSEL)?.let { value ->
            outputContent = value
            if (value != 0 && outputContentFixes < 1) {
                outputContentFixes++
                main.post {
                    note("output content was $value, forcing accelerometer + gyro + angle")
                    forceOutputContent()
                }
            }
        }
        val register = awaitingRegister
        if (register >= 0 && reply.short(register) != null) awaitingRegister = -1
    }

    // ---------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------

    private val watchdog = object : Runnable {
        override fun run() {
            if (!connected) return
            val now = SystemClock.elapsedRealtime()
            val reference = if (lastFrameAt > 0L) lastFrameAt else subscribedAt
            val age = now - reference
            if (age > STALE_TEARDOWN_MS) {
                note("no frames for $age ms - tearing the link down")
                shutDown("No data for ${age / 1000} s.")
                retryLater()
                return
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val diagnosticsPoll = object : Runnable {
        override fun run() {
            if (!connected) return
            readRegister(Wt9011Protocol.REG_BATTERY)
            main.postDelayed({ readRegister(Wt9011Protocol.REG_TEMP) }, 400)
            main.postDelayed(this, DIAGNOSTICS_PERIOD_MS)
        }
    }

    private fun scheduleDiagnostics() {
        main.removeCallbacks(diagnosticsPoll)
        main.postDelayed(diagnosticsPoll, DIAGNOSTICS_PERIOD_MS)
    }

    private fun readOutputContent() {
        readRegister(Wt9011Protocol.REG_AGPVSEL)
    }

    @SuppressLint("MissingPermission")
    private fun runSetupBlock(then: () -> Unit) {
        if (setupInFlight) {
            // Two settings sequences must never interleave. The next connection redoes the block
            // with whatever mount is selected then.
            configured = false
            saveSetup()
            note("set-up already running; the new installation direction waits for the next connection")
            return
        }
        setupInFlight = true
        val plane = mountPosition.plane
        val commands = Wt9011Protocol.setup(vertical = plane == MountPlane.VERTICAL)
        writeSequence(commands, WRITE_GAP_MS) { complete ->
            setupInFlight = false
            // Only a block that went out whole counts: a dropped link or a refused write leaves
            // the sensor half set up, and the next connection has to try again. If the mount
            // changed while the block was in flight, the direction just written is stale.
            val applied = complete && mountPosition.plane == plane
            configured = applied
            saveSetup()
            if (applied) {
                note("set-up done; verifying the output content")
            } else {
                note("set-up incomplete or stale; it runs again on the next connection")
            }
            then()
            scheduleDiagnostics()
        }
    }

    /**
     * Writes one command per [gapMs] with response, in order. The device needs a moment between
     * settings writes and only accepts them for ten seconds after the unlock, so the whole block
     * runs in roughly a second and never overlaps two commands.
     */
    private fun writeSequence(commands: List<ByteArray>, gapMs: Long, then: (complete: Boolean) -> Unit) {
        val queue = ArrayDeque(commands)
        fun step() {
            if (!connected) {
                then(false)
                return
            }
            if (queue.isEmpty()) {
                then(true)
                return
            }
            val next = queue.removeFirst()
            if (!writeCommand(next)) {
                note("a settings write was refused by the stack")
                then(false)
                return
            }
            main.postDelayed({ step() }, gapMs)
        }
        step()
    }

    private fun forceOutputContent() {
        val commands = listOf(
            Wt9011Protocol.unlock(),
            Wt9011Protocol.outputAccelGyroAngle(),
            Wt9011Protocol.save()
        )
        writeSequence(commands, WRITE_GAP_MS) { readOutputContent() }
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(bytes: ByteArray): Boolean {
        val gatt = gatt ?: return false
        val characteristic = commandCharacteristic ?: return false
        if (!hasPermissions()) return false
        return try {
            // Write with response on purpose: these are settings and register reads, and the
            // device answers a settings write before it accepts the next one.
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } catch (_: SecurityException) {
            false
        }
    }

    private fun readRegister(register: Int) {
        if (!connected) return
        awaitingRegister = register
        awaitingTries = REGISTER_TRIES
        attemptRead(register)
    }

    private fun attemptRead(register: Int) {
        if (!connected) {
            awaitingRegister = -1
            return
        }
        if (!writeCommand(Wt9011Protocol.readRegister(register))) {
            awaitingRegister = -1
            return
        }
        main.postDelayed({
            if (awaitingRegister != register) return@postDelayed
            awaitingTries--
            if (awaitingTries > 0) {
                attemptRead(register)
            } else {
                awaitingRegister = -1
                note("no reply for register 0x%02X".format(Locale.ROOT, register))
            }
        }, REGISTER_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------------------------------
    // Permissions and the adapter
    // ---------------------------------------------------------------------------------------

    private fun hasPermissions(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ---------------------------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------------------------

    private fun note(line: String) {
        val stamped = synchronized(clock) { "${clock.format(Date())} $line" }
        synchronized(recent) {
            recent.addLast(stamped)
            while (recent.size > RECENT_LINES) recent.removeFirst()
        }
        log.log("wt9011: $line")
    }

    private fun loadStoredState() {
        mountPosition = readProperties(mountingFile)?.let { properties ->
            if (properties.getProperty("model") == STORAGE_MODEL) {
                val plane = properties.getProperty("plane")
                    ?.let { runCatching { MountPlane.valueOf(it) }.getOrNull() }
                    ?: MountPlane.FLAT
                val yaw = properties.getProperty("yaw")
                    ?.let { runCatching { MountYaw.valueOf(it) }.getOrNull() }
                    ?: MountYaw.FRONT
                val face = properties.getProperty("face")
                    ?.let { runCatching { MountFace.valueOf(it) }.getOrNull() }
                    ?: MountFace.UP
                // An on-edge mount has no label-up/label-down choice; a stale file must not throw.
                MountPosition(
                    yaw = yaw,
                    face = if (plane == MountPlane.VERTICAL) MountFace.UP else face,
                    plane = plane
                )
            } else {
                note("mount reset to default: long axis forward, label up")
                MountPosition.DEFAULT
            }
        } ?: MountPosition.DEFAULT

        val zeroProperties = readProperties(zeroFile)
        val sameModel = zeroProperties?.getProperty("model") == STORAGE_MODEL
        val sameMount = zeroProperties?.getProperty("mount") == mountTag()
        zero = if (zeroProperties != null && sameModel && sameMount) {
            val lean = zeroProperties.getProperty("leanDeg")?.toFloatOrNull()
            val pitch = zeroProperties.getProperty("pitchDeg")?.toFloatOrNull()
            val forward = zeroProperties.getProperty("forwardG")?.toFloatOrNull()
            val lateral = zeroProperties.getProperty("lateralG")?.toFloatOrNull()
            val up = zeroProperties.getProperty("upG")?.toFloatOrNull()
            val takenAt = zeroProperties.getProperty("takenAt")?.toLongOrNull()
            if (lean != null && pitch != null && forward != null && lateral != null &&
                up != null && takenAt != null
            ) {
                Zero(lean, pitch, forward, lateral, up, takenAt)
            } else {
                null
            }
        } else {
            if (zeroProperties != null) {
                zeroFile.delete()
                note("zero cleared: the axis model or mount changed; capture it again with the bike upright")
            }
            null
        }
        readProperties(setupFile)?.let { properties ->
            val storedConfigured = properties.getProperty("configured")?.toBooleanStrictOrNull() ?: false
            val storedModel = properties.getProperty("setupModel")
            configured = storedConfigured && storedModel == SETUP_MODEL
            if (storedConfigured && storedModel != SETUP_MODEL) {
                note("set-up recipe changed; the sensor is configured again on the next connection")
            }
            autoConnect = properties.getProperty("autoConnect")?.toBooleanStrictOrNull() ?: true
            pinnedAddress = properties.getProperty("deviceAddress")?.takeIf { it.isNotBlank() }
        }
    }

    private fun mountTag(): String =
        "${mountPosition.plane.name}/${mountPosition.yaw.name}/${mountPosition.face.name}"

    private fun saveZero(zero: Zero) {
        writeProperties(
            zeroFile,
            Properties().apply {
                setProperty("model", STORAGE_MODEL)
                setProperty("mount", mountTag())
                setProperty("leanDeg", zero.leanDeg.toString())
                setProperty("pitchDeg", zero.pitchDeg.toString())
                setProperty("forwardG", zero.forwardG.toString())
                setProperty("lateralG", zero.lateralG.toString())
                setProperty("upG", zero.upG.toString())
                setProperty("takenAt", zero.takenAt.toString())
            }
        )
    }

    private fun saveMounting() {
        val current = mountPosition
        writeProperties(
            mountingFile,
            Properties().apply {
                setProperty("model", STORAGE_MODEL)
                setProperty("plane", current.plane.name)
                setProperty("yaw", current.yaw.name)
                setProperty("face", current.face.name)
            }
        )
    }

    private fun saveSetup() {
        writeProperties(
            setupFile,
            Properties().apply {
                setProperty("setupModel", SETUP_MODEL)
                setProperty("configured", configured.toString())
                setProperty("autoConnect", autoConnect.toString())
                pinnedAddress?.let { setProperty("deviceAddress", it) }
            }
        )
    }

    private fun readProperties(file: File): Properties? {
        if (!file.exists()) return null
        return try {
            Properties().apply { FileInputStream(file).use { load(it) } }
        } catch (_: Exception) {
            // A half-written file is not worth a crash: the rider re-zeros and carries on.
            null
        }
    }

    private fun writeProperties(file: File, properties: Properties) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { properties.store(it, "MOTO-HUB WT9011 module") }
        } catch (_: Exception) {
            note("could not write ${file.name}")
        }
    }

    private companion object {
        const val STORAGE_MODEL = "2"
        /** Bump when the setup recipe changes so an upgraded module reconfigures the sensor. */
        const val SETUP_MODEL = "2"
        const val SCAN_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_SCAN_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 2_000L
        const val IDLE_RETRY_MS = 30_000L
        const val WATCHDOG_MS = 250L
        const val STALE_TEARDOWN_MS = 2_000L
        const val WRITE_GAP_MS = 150L
        const val MTU_DELAY_MS = 700L
        const val ZERO_MAX_AGE_MS = 1_000L
        const val REGISTER_TIMEOUT_MS = 200L
        const val REGISTER_TRIES = 3
        const val DIAGNOSTICS_PERIOD_MS = 30_000L
        const val DISPLACEMENT_MS = 2_000L
        const val RECENT_LINES = 8
    }
}

/** A coherent read of everything the diagnostics screen shows. */
class Wt9011Snapshot(
    val status: String,
    val connected: Boolean,
    val deviceLabel: String?,
    /** The last frame's raw channels, before any of the sign constants: what the bench tests read. */
    val raw: Wt9011Raw?,
    /** The ride model: zero and mount applied. */
    val ride: Packet?,
    /** Directional peaks at the sensor's full rate, not the screen's poll rate. */
    val peaks: Peaks,
    val packetsPerSecond: Int,
    val frameAgeMs: Long,
    val batteryPercent: Int?,
    val batteryRaw: Int?,
    val temperatureC: Float?,
    val outputContent: Int?,
    val zero: Zero?,
    val mountPosition: MountPosition,
    val configured: Boolean,
    val displacementSuspected: Boolean,
    val autoConnect: Boolean,
    val received: Long,
    val recent: List<String>,
    /** The remembered sensor's address, or null before the first connection. */
    val pinnedAddress: String?
)

/** What changed when the rider picked a different mount. */
class MountChange(
    /** A captured zero belonged to the old frame and was thrown away. */
    val zeroCleared: Boolean,
    /** Flat vs on-edge changed, so the sensor's installation direction has to be rewritten. */
    val directionChanged: Boolean
)
