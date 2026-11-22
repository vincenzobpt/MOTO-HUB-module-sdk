// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.github.sur00t.wt9011

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleLiveState
import io.motohub.android.module.ModuleLiveStatus
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.ModuleMotionListener
import io.motohub.android.module.ModuleMotionSource
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The module the app loads: a WT9011DCL on the bike, read directly over Bluetooth LE.
 *
 * The sensor is a BLE peripheral that streams as soon as somebody subscribes, so this module is the
 * central: it scans for it, connects, subscribes and decodes. Nothing else is needed between the
 * sensor and the phone.
 *
 * Three surfaces, in increasing order of how much they cost the rider's attention:
 *
 * - The screen, which is the bench: link state, processed lean, the mount picker, the zero, and the
 *   battery. Every empirical constant in `Wt9011Axes` is checked here on the bike.
 * - The Ride-page pill, which is the only lean a rider sees while riding: the contract has no
 *   lean-HUD, and this module does not draw on the Ride page.
 * - The log, which gets one line per notable event so a diagnostics report stays one file.
 *
 * And one that costs none: every sample goes to MOTO-HUB itself (ModuleMotionSource, contract 43),
 * which records it in the trip and shows it on the Ride Dashboard instead of the phone's sensors.
 *
 * Written by sur00t (https://github.com/sur00t).
 */
class Wt9011Entry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = Wt9011Module(host)
}

private class Wt9011Module(private val host: MotoHubModuleHost) :
    MotoHubModule,
    ModuleFeatures,
    ModuleLiveStatus,
    ModuleMotionSource {

    // Must agree with motohubModule { } in build.gradle.kts.
    override val manifest = ModuleManifest(
        id = "wt9011",
        version = "0.3.0",
        contractVersion = 43,
        entryClass = "io.github.sur00t.wt9011.Wt9011Entry",
        displayName = "WT9011 IMU",
        description = "Lean, g-forces and heading from a WitMotion WT9011DCL over Bluetooth LE."
    )

    private val link = Wt9011Link(host.context, host.storage, host.log)

    private val liveState = MutableStateFlow<ModuleLiveState?>(null)
    override val live: StateFlow<ModuleLiveState?> = liveState

    private val main = Handler(Looper.getMainLooper())
    private var lastPillLabel: String? = null

    /**
     * The pill is driven from here and not from the screen: the whole point of it is that lean is
     * on the Ride page while the diagnostics screen is closed. Twice a second is enough to read a
     * lean angle and cheap enough to leave running.
     */
    private val pillTicker = object : Runnable {
        override fun run() {
            publishPill()
            main.postDelayed(this, PILL_PERIOD_MS)
        }
    }

    init {
        // `create` may run on a background thread; everything below lands on the main looper.
        main.post {
            // The module always looks for the IMU. The first WT sensor it finds is pinned, and
            // from then on only that address is used. A manual Disconnect stops the search for the
            // current session only; the next module creation starts it again.
            link.connect()
        }
        main.postDelayed(pillTicker, PILL_PERIOD_MS)
    }

    override fun features(): List<ModuleFeature> = listOf(
        ModuleFeature(
            id = FEATURE_DIAGNOSTICS,
            title = "WT9011 IMU",
            description = "Lean, g-forces and heading from a WitMotion WT9011DCL over Bluetooth LE.",
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> DiagnosticsScreen(onBack) }
        )
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when {
        type.isInstance(this) -> this as T
        else -> null
    }

    override fun addMotionListener(listener: ModuleMotionListener) = link.addMotionListener(listener)

    override fun removeMotionListener(listener: ModuleMotionListener) = link.removeMotionListener(listener)

    override fun release() {
        main.removeCallbacks(pillTicker)
        liveState.value = null
        link.release()
    }

    private fun publishPill() {
        val snapshot = link.snapshot()
        val ride = snapshot.ride
        val fresh = snapshot.connected && snapshot.frameAgeMs in 0..PILL_STALE_MS
        val label: String? = if (fresh && ride != null) {
            val side = if (ride.leanDeg >= 0f) "right" else "left"
            String.format(
                Locale.ROOT,
                "Lean %.0f° %s · %.2f g",
                abs(ride.leanDeg),
                side,
                ride.longG
            )
        } else {
            null
        }
        if (label != lastPillLabel) {
            lastPillLabel = label
            liveState.value = label?.let { ModuleLiveState(it, FEATURE_DIAGNOSTICS) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The screen
    // ---------------------------------------------------------------------------------------

    @Composable
    private fun DiagnosticsScreen(onBack: () -> Unit) {
        val ui = host.ui
        var snapshot by remember { mutableStateOf(link.snapshot()) }
        var message by remember { mutableStateOf<String?>(null) }
        var advanced by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            // Ten redraws a second keeps the horizon and the G dot smooth without redrawing
            // every 100 Hz frame the sensor sends. Peaks are tracked in the link, not here.
            while (true) {
                snapshot = link.snapshot()
                delay(RENDER_PERIOD_MS)
            }
        }

        val ride = snapshot.ride

        ui.Backdrop(onBack = onBack) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(ScreenTop, ScreenBottom)))
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                HeaderStrip(
                    snapshot = snapshot,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    onConnect = {
                        if (snapshot.connected) link.reconnect() else link.connect()
                        message = if (snapshot.connected) {
                            "Reconnecting to the sensor…"
                        } else {
                            "Looking for a WT sensor…"
                        }
                    },
                    onZero = {
                        message = link.captureZero()
                    }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp)
                ) {
                    message?.let {
                        Text(
                            text = it,
                            color = AmberText,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    LeanCard(ride = ride, peaks = snapshot.peaks)

                    Spacer(Modifier.height(12.dp))
                    GForceCard(
                        ride = ride,
                        peaks = snapshot.peaks,
                        onResetPeaks = {
                            message = link.resetPeaks()
                        }
                    )

                    Spacer(Modifier.height(12.dp))
                    MountSection(
                        mount = snapshot.mountPosition,
                        onSelect = { position ->
                            val change = link.setMountPosition(position)
                            val parts = mutableListOf(
                                "Mount changed to ${position.yaw.label.lowercase(Locale.ROOT)}" +
                                    if (position.plane == MountPlane.VERTICAL) ", on edge."
                                    else ", label up."
                            )
                            if (change.zeroCleared) parts += "The old zero was cleared."
                            if (change.directionChanged) {
                                parts += if (snapshot.connected) {
                                    "Switching the sensor to the vertical installation direction…"
                                } else {
                                    "The installation direction is written on the next connection."
                                }
                            }
                            if (change.zeroCleared || change.directionChanged) {
                                parts += "Tap ZERO with the bike upright once it streams again."
                            }
                            message = parts.joinToString(" ")
                        }
                    )

                    Spacer(Modifier.height(12.dp))
                    AdvancedHeader(open = advanced, onClick = { advanced = !advanced })
                    if (advanced) {
                        Spacer(Modifier.height(8.dp))
                        AdvancedContent(
                            snapshot = snapshot,
                            onMessage = { message = it }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AdvancedContent(
        snapshot: Wt9011Snapshot,
        onMessage: (String) -> Unit
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
                .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            AdvancedLabel("Ride")
            DarkFact("Heading", snapshot.ride?.let { heading(it.yawDeg) } ?: "—")
            DarkFact("Forward", snapshot.ride?.let { gForces(it.longG) } ?: "—")
            DarkFact("Lateral", snapshot.ride?.let { gForces(it.latG) } ?: "—")
            DarkFact("Vertical", snapshot.ride?.let { gForces(it.vertG) } ?: "—")

            AdvancedLabel("Link")
            DarkFact("Status", snapshot.status)
            DarkFact("Sensor", snapshot.deviceLabel ?: "—")
            DarkFact(
                "Frames",
                if (snapshot.connected) "${snapshot.packetsPerSecond} per second" else "—"
            )
            DarkFact("Last frame", ageText(snapshot.frameAgeMs))
            DarkFact("Frames received", snapshot.received.toString())
            Spacer(Modifier.height(6.dp))
            DarkAction(
                title = if (snapshot.connected) "Reconnect" else "Connect",
                description = if (snapshot.connected) {
                    "Drop the link and look for the sensor again."
                } else {
                    "Look for a WT sensor and subscribe."
                }
            ) {
                if (snapshot.connected) link.reconnect() else link.connect()
                onMessage(if (snapshot.connected) "Reconnecting…" else "Looking for a WT sensor…")
            }
            Spacer(Modifier.height(6.dp))
            DarkAction("Disconnect", "Stop the link; nothing reconnects until Connect.") {
                link.disconnect()
                onMessage("Disconnected.")
            }
            snapshot.pinnedAddress?.let { address ->
                Spacer(Modifier.height(6.dp))
                DarkAction("Forget this sensor", "Only $address is used. Tap to forget it and search for a replacement.") {
                    onMessage(link.forgetSensor())
                }
            }

            AdvancedLabel("Zero and set-up")
            DarkAction("Clear zero", "Throw the captured attitude away.") {
                onMessage(link.clearZero())
            }
            Spacer(Modifier.height(6.dp))
            DarkAction(
                "Send set-up",
                if (snapshot.configured) {
                    "Rewrite rate, bandwidth, 9-axis and installation direction."
                } else {
                    "Write the set-up block now instead of waiting for the next connection."
                }
            ) {
                onMessage(link.configureSensor())
            }

            AdvancedLabel("Power and health")
            DarkFact(
                "Battery",
                snapshot.batteryPercent?.let { String.format(Locale.ROOT, "%d %%", it) } ?: "—"
            )
            DarkFact("Battery raw", snapshot.batteryRaw?.toString() ?: "—")
            DarkFact(
                "Temperature",
                snapshot.temperatureC?.let { String.format(Locale.ROOT, "%.1f °C", it) } ?: "—"
            )
            DarkFact("Auto-connect", if (snapshot.autoConnect) "on" else "off")

            AdvancedLabel("Recent")
            Text(
                text = snapshot.recent.joinToString("\n").ifEmpty { "Nothing yet." },
                color = MutedText,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
    }

    private companion object {
        const val FEATURE_DIAGNOSTICS = "wt9011-diagnostics"
        const val RENDER_PERIOD_MS = 100L
        const val PILL_PERIOD_MS = 500L
        const val PILL_STALE_MS = 1000L
    }
}

// ---------------------------------------------------------------------------------------------
// Palette and dark cards
// ---------------------------------------------------------------------------------------------

private val ScreenTop = Color(0xFF0B0C0C)
private val ScreenBottom = Color(0xFF070808)
private val CardTop = Color(0xFF191A19)
private val CardBottom = Color(0xFF131413)
private val CardBorder = Color(0xFF2A2D2C)
private val WhiteText = Color(0xFFF3F4F4)
private val SublineText = Color(0xFFC7CCCC)
private val MutedText = Color(0xFFA8ADAD)
private val ScaleText = Color(0xFFE5E8E8)
private val AmberText = Color(0xFFF39C38)
private val PeakLabel = Color(0xFFE59438)
private val ValueText = Color(0xFFF3F5F5)
private val PeakBlue = Color(0xFF4DA6FF)
private val SkyTop = Color(0xFF1976BD)
private val SkyBottom = Color(0xFF2692DF)
private val GroundTop = Color(0xFF44484B)
private val GroundBottom = Color(0xFF2B2E30)
private val PlotCenter = Color(0xFF2C2214)
private val PlotEdge = Color(0xFF140F09)
private val AxisText = Color(0xFFC9CCCC)
private val ConnectText = Color(0xFF9BE3B0)
private val ConnectBorder = Color(0xFF395240)
private val ConnectFill = Color(0xFF171B18)
private val ZeroText = Color(0xFFD9DF72)
private val ZeroBorder = Color(0xFF535733)
private val ZeroFill = Color(0xFF1B1C15)
private val ResetFill = Color(0xFF1D1E1D)
private val ResetBorder = Color(0xFF3C3F3E)
private val ResetText = Color(0xFFC7C9C9)
private val GreenDot = Color(0xFF29B854)
private val RedDot = Color(0xFFE53935)

private fun gForces(value: Float): String = String.format(Locale.ROOT, "%+.2f g", value)

private fun Modifier.darkCard(): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(22.dp))
    .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
    .border(1.2.dp, CardBorder, RoundedCornerShape(22.dp))

@Composable
private fun HeaderStrip(
    snapshot: Wt9011Snapshot,
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
    onZero: () -> Unit
) {
    val fresh = snapshot.connected && snapshot.frameAgeMs in 0..1_000L
    Column(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (fresh) GreenDot else RedDot)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "WT9011 IMU",
                    color = WhiteText,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sensorLine(snapshot),
                    color = SublineText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HeaderButton("CONNECT", ConnectText, ConnectBorder, ConnectFill, onConnect)
                HeaderButton("ZERO", ZeroText, ZeroBorder, ZeroFill, onZero)
            }
        }
        Text(
            text = calibrationLine(snapshot),
            color = MutedText,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 17.dp, top = 8.dp)
        )
    }
}

private fun sensorLine(snapshot: Wt9011Snapshot): String {
    val name = snapshot.deviceLabel ?: "No sensor"
    val hz = if (snapshot.connected) "${snapshot.packetsPerSecond}Hz" else "—Hz"
    val ms = if (snapshot.frameAgeMs >= 0L) "${snapshot.frameAgeMs}ms" else "—ms"
    return "$name | $hz • $ms"
}

private fun calibrationLine(snapshot: Wt9011Snapshot): String {
    val zero = snapshot.zero ?: return "Not zeroed — tap ZERO with the bike upright."
    return String.format(
        Locale.ROOT,
        "Zeroed at lean %.1f°, pitch %.1f°.",
        zero.leanDeg,
        zero.pitchDeg
    )
}

@Composable
private fun HeaderButton(
    label: String,
    textColor: Color,
    borderColor: Color,
    fill: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .background(fill)
            .border(1.2.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun LeanCard(ride: Packet?, peaks: Peaks) {
    val lean = ride?.leanDeg ?: 0f
    val nose = ride?.pitchDeg ?: 0f
    Column(
        Modifier
            .darkCard()
            .padding(12.dp)
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
        ) {
            val cx = size.width / 2f
            val cy = size.height * 0.62f
            val arcR = size.width * 0.255f
            val circleR = arcR * 0.846f
            val rad = PI.toFloat() / 180f

            // Compose angles: 0 = right, 90 = down, 270 = top. So 180…360 is the top semicircle,
            // -90 maps to the left end, 0 to the top and +90 to the right end.
            drawArc(
                color = WhiteText,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - arcR, cy - arcR),
                size = Size(arcR * 2f, arcR * 2f),
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            )
            for (value in -90..90 step 15) {
                val angle = (270f + value) * rad
                val length = when (abs(value)) {
                    90 -> 10.dp.toPx()
                    45 -> 7.dp.toPx()
                    else -> 5.dp.toPx()
                }
                val outer = Offset(cx + arcR * cos(angle), cy + arcR * sin(angle))
                val inner = Offset(
                    cx + (arcR - length) * cos(angle),
                    cy + (arcR - length) * sin(angle)
                )
                drawLine(
                    color = WhiteText,
                    start = inner,
                    end = outer,
                    strokeWidth = if (abs(value) % 30 == 0) 2.dp.toPx() else 1.4.dp.toPx()
                )
            }
            drawInstrumentLabel(
                "-90",
                cx - arcR - 6.dp.toPx(),
                cy + 16.dp.toPx(),
                ScaleText,
                12.sp.toPx(),
                android.graphics.Paint.Align.RIGHT
            )
            drawInstrumentLabel(
                "0",
                cx,
                cy - arcR - 16.dp.toPx(),
                WhiteText,
                13.sp.toPx()
            )
            drawInstrumentLabel(
                "+90",
                cx + arcR + 6.dp.toPx(),
                cy + 16.dp.toPx(),
                ScaleText,
                12.sp.toPx(),
                android.graphics.Paint.Align.LEFT
            )

            // Downward zero triangle at the top.
            val zero = Path().apply {
                moveTo(cx, cy - arcR - 3.dp.toPx())
                lineTo(cx - 5.dp.toPx(), cy - arcR - 11.dp.toPx())
                lineTo(cx + 5.dp.toPx(), cy - arcR - 11.dp.toPx())
                close()
            }
            drawPath(zero, WhiteText)

            fun peakDot(peakDeg: Float, color: Color) {
                val angle = (270f + peakDeg) * rad
                drawCircle(
                    color = color,
                    radius = 3.5.dp.toPx(),
                    center = Offset(
                        cx + (arcR + 6.dp.toPx()) * cos(angle),
                        cy + (arcR + 6.dp.toPx()) * sin(angle)
                    )
                )
            }
            if (peaks.maxLeftLean > 0.5f) peakDot(-peaks.maxLeftLean.coerceAtMost(90f), PeakBlue)
            if (peaks.maxRightLean > 0.5f) peakDot(peaks.maxRightLean.coerceAtMost(90f), AmberText)

            // Active lean pointer.
            rotate(degrees = lean.coerceIn(-90f, 90f), pivot = Offset(cx, cy)) {
                val pointer = Path().apply {
                    moveTo(cx, cy - arcR + 3.dp.toPx())
                    lineTo(cx - 4.5.dp.toPx(), cy - arcR - 7.dp.toPx())
                    lineTo(cx + 4.5.dp.toPx(), cy - arcR - 7.dp.toPx())
                    close()
                }
                drawPath(pointer, AmberText)
            }

            // Gauge ring and horizon.
            drawCircle(Color(0xFF0D0E0E), circleR + 2.dp.toPx(), Offset(cx, cy))
            drawCircle(
                color = Color(0xFF2F3231),
                radius = circleR + 2.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.4.dp.toPx())
            )

            val clip = Path().apply {
                addOval(
                    androidx.compose.ui.geometry.Rect(
                        left = cx - circleR,
                        top = cy - circleR,
                        right = cx + circleR,
                        bottom = cy + circleR
                    )
                )
            }
            clipPath(clip) {
                // A right bank (leanDeg > 0) rotates the world counterclockwise, so the horizon
                // rotates by -lean while the pointer on the scale rotates by +lean.
                rotate(degrees = -lean, pivot = Offset(cx, cy)) {
                    translate(left = 0f, top = nose * 1.2f * 1.dp.toPx()) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(SkyTop, SkyBottom),
                                startY = cy - circleR * 2f,
                                endY = cy
                            ),
                            topLeft = Offset(cx - circleR * 2f, cy - circleR * 2f),
                            size = Size(circleR * 4f, circleR * 2f)
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(GroundTop, GroundBottom),
                                startY = cy,
                                endY = cy + circleR * 2f
                            ),
                            topLeft = Offset(cx - circleR * 2f, cy),
                            size = Size(circleR * 4f, circleR * 2f)
                        )
                        drawLine(
                            color = WhiteText,
                            start = Offset(cx - circleR * 2f, cy),
                            end = Offset(cx + circleR * 2f, cy),
                            strokeWidth = 2.dp.toPx()
                        )
                        val ladder = listOf(
                            0.193f to 0.295f,
                            0.364f to 0.170f,
                            0.534f to 0.386f
                        )
                        for ((offset, half) in ladder) {
                            for (sign in listOf(-1f, 1f)) {
                                val y = cy + sign * offset * circleR
                                drawLine(
                                    color = WhiteText.copy(alpha = 0.85f),
                                    start = Offset(cx - half * circleR, y),
                                    end = Offset(cx + half * circleR, y),
                                    strokeWidth = 1.6.dp.toPx()
                                )
                            }
                        }
                    }
                }
            }

            drawReticle(cx, cy, circleR, WhiteText)
        }

        Text(
            text = leanText(lean, ride != null),
            modifier = Modifier.fillMaxWidth(),
            color = AmberText,
            fontSize = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Metric("MAX L", String.format(Locale.ROOT, "%.0f°", peaks.maxLeftLean))
            Metric(
                "PITCH",
                if (ride != null) String.format(Locale.ROOT, "%+.1f°", nose) else "—"
            )
            Metric("MAX R", String.format(Locale.ROOT, "%.0f°", peaks.maxRightLean))
        }
    }
}

private fun leanText(lean: Float, hasRide: Boolean): String {
    if (!hasRide) return "—"
    if (abs(lean) < 0.05f) return "0.0°"
    val side = if (lean > 0f) "R" else "L"
    return String.format(Locale.ROOT, "%.1f° %s", abs(lean), side)
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Text(value, color = ValueText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

private fun DrawScope.drawReticle(cx: Float, cy: Float, r: Float, color: Color) {
    val left = Path().apply {
        moveTo(cx - 0.84f * r, cy - 0.017f * r)
        lineTo(cx - 0.24f * r, cy - 0.017f * r)
        lineTo(cx - 0.24f * r, cy + 0.284f * r)
        lineTo(cx - 0.28f * r, cy + 0.284f * r)
        lineTo(cx - 0.28f * r, cy + 0.020f * r)
        lineTo(cx - 0.84f * r, cy + 0.020f * r)
        close()
    }
    drawPath(left, color)
    val right = Path().apply {
        moveTo(cx + 0.84f * r, cy - 0.017f * r)
        lineTo(cx + 0.24f * r, cy - 0.017f * r)
        lineTo(cx + 0.24f * r, cy + 0.284f * r)
        lineTo(cx + 0.28f * r, cy + 0.284f * r)
        lineTo(cx + 0.28f * r, cy + 0.020f * r)
        lineTo(cx + 0.84f * r, cy + 0.020f * r)
        close()
    }
    drawPath(right, color)
    val hub = Path().apply {
        moveTo(cx, cy - 0.097f * r)
        lineTo(cx + 0.091f * r, cy - 0.045f * r)
        lineTo(cx + 0.091f * r, cy + 0.045f * r)
        lineTo(cx, cy + 0.097f * r)
        lineTo(cx - 0.091f * r, cy + 0.045f * r)
        lineTo(cx - 0.091f * r, cy - 0.045f * r)
        close()
    }
    drawPath(hub, color)
}

@Composable
private fun GForceCard(
    ride: Packet?,
    peaks: Peaks,
    onResetPeaks: () -> Unit
) {
    Row(
        Modifier
            .darkCard()
            .padding(12.dp)
    ) {
        Canvas(
            Modifier
                .weight(1.15f)
                .height(200.dp)
        ) {
            val left = 28.dp.toPx()
            val top = 10.dp.toPx()
            val right = size.width - 16.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val plotW = right - left
            val plotH = bottom - top
            val cx = left + plotW / 2f
            val cy = top + plotH / 2f
            val halfW = plotW / 2f
            val halfH = plotH / 2f

            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(PlotCenter, PlotEdge),
                    center = Offset(cx, cy),
                    radius = max(plotW, plotH) * 0.65f
                ),
                topLeft = Offset(left, top),
                size = Size(plotW, plotH)
            )
            drawRect(
                color = Color(0xFF6C6E6D),
                topLeft = Offset(left, top),
                size = Size(plotW, plotH),
                style = Stroke(1.2.dp.toPx())
            )

            val grid = Color(0xFF8F9190).copy(alpha = 0.45f)
            for (fraction in listOf(1f / 3f, 2f / 3f, 1f)) {
                drawCircle(
                    color = grid,
                    radius = min(halfW, halfH) * fraction,
                    center = Offset(cx, cy),
                    style = Stroke(1.dp.toPx())
                )
            }
            for (lateral in listOf(-1f, 1f)) {
                val x = cx + (lateral / 2f) * halfW
                drawLine(grid, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            }
            for (longitudinal in listOf(-2f, -1.5f, -1f, -0.5f, 0.5f, 1f, 1.5f, 2f)) {
                val y = cy - (longitudinal / 2.5f) * halfH
                drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            drawLine(
                color = Color(0xFFB4B6B5),
                start = Offset(cx, top),
                end = Offset(cx, bottom),
                strokeWidth = 1.3.dp.toPx()
            )
            drawLine(
                color = Color(0xFFB4B6B5),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = 1.3.dp.toPx()
            )

            // +latG is right and +longG is forward, so x grows to the right and y shrinks upward.
            ride?.let { r ->
                val x = cx + (r.latG / 2f).coerceIn(-1f, 1f) * halfW
                val y = cy - (r.longG / 2.5f).coerceIn(-1f, 1f) * halfH
                drawCircle(AmberText.copy(alpha = 0.35f), 11.dp.toPx(), Offset(x, y))
                drawCircle(Color(0xFFFFF8E8), 5.dp.toPx(), Offset(x, y))
                drawCircle(
                    color = AmberText,
                    radius = 5.dp.toPx(),
                    center = Offset(x, y),
                    style = Stroke(1.5.dp.toPx())
                )
            }

            val lateralLabels = listOf(
                "-2.0G" to left,
                "-1.0G" to cx - halfW / 2f,
                "0.0" to cx,
                "1.0G" to cx + halfW / 2f,
                "2.0G" to right
            )
            for ((text, x) in lateralLabels) {
                drawInstrumentLabel(text, x, size.height - 4.dp.toPx(), AxisText, 8.sp.toPx())
            }
            for (index in -5..5) {
                val g = index * 0.5f
                val y = cy - (g / 2.5f) * halfH
                val text = if (g == 0f) "0.0G" else String.format(Locale.ROOT, "%.1fG", g)
                drawInstrumentLabel(
                    text,
                    2.dp.toPx(),
                    y + 3.dp.toPx(),
                    AxisText,
                    8.sp.toPx(),
                    android.graphics.Paint.Align.LEFT
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PeakCell("MAX L", peaks.maxLeftG, Modifier.weight(1f))
                PeakCell("MAX R", peaks.maxRightG, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PeakCell("ACCEL", peaks.maxAccel, Modifier.weight(1f))
                PeakCell("BRAKE", peaks.maxBrake, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            ResetPeaksButton(onResetPeaks)
        }
    }
}

@Composable
private fun PeakCell(label: String, value: Float, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = PeakLabel,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Text(
            text = String.format(Locale.ROOT, "%.1fG", value),
            color = ValueText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ResetPeaksButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(shape)
            .background(ResetFill)
            .border(1.2.dp, ResetBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "RESET PEAKS",
            color = ResetText,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp
        )
    }
}

@Composable
private fun MountSection(mount: MountPosition, onSelect: (MountPosition) -> Unit) {
    Column(
        Modifier
            .darkCard()
            .padding(12.dp)
    ) {
        Text(
            text = "MOUNTING",
            color = MutedText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = mount.label,
            color = WhiteText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        Text(
            text = "Flat · label up",
            color = MutedText,
            fontSize = 10.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MountYaw.values().forEach { yaw ->
                val position = MountPosition(yaw = yaw, face = MountFace.UP, plane = MountPlane.FLAT)
                MountTile(
                    position = position,
                    selected = position == mount,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(position) }
                )
            }
        }
        Text(
            text = "On edge · label face (+Y up, USB down)",
            color = MutedText,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MountYaw.values().forEach { yaw ->
                val position = MountPosition(yaw = yaw, face = MountFace.UP, plane = MountPlane.VERTICAL)
                MountTile(
                    position = position,
                    selected = position == mount,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(position) }
                )
            }
        }
    }
}

@Composable
private fun MountTile(
    position: MountPosition,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = 74.dp)
            .clip(shape)
            .background(if (selected) ZeroFill else CardTop)
            .border(
                width = if (selected) 1.8.dp else 1.dp,
                color = if (selected) ZeroText else CardBorder,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MountGlyph(position)
            Text(
                text = position.yaw.label,
                color = if (selected) ZeroText else SublineText,
                fontSize = 10.sp,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MountGlyph(position: MountPosition) {
    val vertical = position.plane == MountPlane.VERTICAL
    val down = !vertical && position.face == MountFace.DOWN
    val accent = if (down) MutedText else ZeroText
    val angle = position.yaw.ordinal * 90f

    Canvas(Modifier.size(42.dp, 34.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val halfW = 8.dp.toPx()
        val halfH = 12.dp.toPx()
        rotate(degrees = angle, pivot = Offset(cx, cy)) {
            if (vertical) {
                val bar = Size(27.dp.toPx(), 5.dp.toPx())
                drawRoundRect(
                    color = accent.copy(alpha = 0.20f),
                    topLeft = Offset(cx - bar.width / 2f, cy - bar.height / 2f),
                    size = bar,
                    cornerRadius = CornerRadius(2.5.dp.toPx())
                )
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(cx - bar.width / 2f, cy - bar.height / 2f),
                    size = bar,
                    cornerRadius = CornerRadius(2.5.dp.toPx()),
                    style = Stroke(width = 1.3.dp.toPx())
                )
                drawLine(
                    accent,
                    Offset(cx, cy - 4.dp.toPx()),
                    Offset(cx, cy - 10.dp.toPx()),
                    1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    accent,
                    Offset(cx, cy - 10.dp.toPx()),
                    Offset(cx - 3.dp.toPx(), cy - 6.dp.toPx()),
                    1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    accent,
                    Offset(cx, cy - 10.dp.toPx()),
                    Offset(cx + 3.dp.toPx(), cy - 6.dp.toPx()),
                    1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            } else {
                drawRoundRect(
                    color = accent.copy(alpha = 0.14f),
                    topLeft = Offset(cx - halfW, cy - halfH),
                    size = Size(halfW * 2f, halfH * 2f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.3.dp.toPx())
                )
                drawLine(
                    accent,
                    Offset(cx, cy + 5.dp.toPx()),
                    Offset(cx, cy - 8.dp.toPx()),
                    1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    accent,
                    Offset(cx, cy - 8.dp.toPx()),
                    Offset(cx - 3.dp.toPx(), cy - 4.dp.toPx()),
                    1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    accent,
                    Offset(cx, cy - 8.dp.toPx()),
                    Offset(cx + 3.dp.toPx(), cy - 4.dp.toPx()),
                    1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(accent, 1.6.dp.toPx(), Offset(cx, cy + 4.dp.toPx()))
            }
        }
    }
}

@Composable
private fun AdvancedHeader(open: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardTop)
            .border(1.dp, CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Advanced",
            color = WhiteText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (open) "−" else "+",
            color = MutedText,
            fontSize = 19.sp
        )
    }
}

@Composable
private fun AdvancedLabel(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        color = MutedText,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 3.dp)
    )
}

@Composable
private fun DarkFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = MutedText, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(value, color = WhiteText, fontSize = 11.sp)
    }
}

@Composable
private fun DarkAction(title: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardTop)
            .border(1.dp, CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Text(title, color = WhiteText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = MutedText, fontSize = 10.sp)
    }
}

/** Canvas text with the platform paint, for the instrument scales. */
private fun DrawScope.drawInstrumentLabel(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizePx: Float,
    align: android.graphics.Paint.Align = android.graphics.Paint.Align.CENTER
) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = align
            textSize = sizePx
        }
        paint.color = color.toArgb()
        canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}
/** The bearing as a rider reads a compass, 0…360, said out loud as magnetic. */
private fun heading(yawDeg: Float): String =
    String.format(Locale.ROOT, "%.0f° magnetic", bearingDeg(yawDeg))

private fun ageText(ageMs: Long): String = when {
    ageMs < 0L -> "—"
    ageMs < 1000L -> String.format(Locale.ROOT, "%d ms ago", ageMs)
    else -> String.format(Locale.ROOT, "%.1f s ago", ageMs / 1000f)
}
