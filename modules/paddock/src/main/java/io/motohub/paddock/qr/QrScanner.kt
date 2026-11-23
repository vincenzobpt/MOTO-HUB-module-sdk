// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
package io.motohub.paddock.qr

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.motohub.android.module.ModuleUi
import java.util.EnumMap
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay

/** What the scanner is doing, for the screen around it to explain. */
enum class ScannerStatus { STARTING, SCANNING, NO_PERMISSION, NO_CAMERA, ERROR }

fun hasCameraPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Asks for the camera permission. The answer is not delivered to us: [QrScanner] polls for it.
 * Does nothing if [activityContext] does not wrap an Activity. If the rider has denied it for good
 * Android shows no dialog and the status stays NO_PERMISSION.
 */
fun requestCameraPermission(activityContext: Context) {
    activityContext.findActivity()?.requestPermissions(arrayOf(Manifest.permission.CAMERA), 4711)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * A live back-camera preview that fills its box (centre-cropped) and reports every QR code it
 * reads through [onResult], on the main thread. A code that stays in view is reported once; it is
 * reported again only after it has been out of sight for 2 s. [onStatus] is called on the main
 * thread whenever the state changes. The camera is released when the screen leaves composition and
 * whenever the Activity is paused, and reopened on resume.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
fun QrScanner(modifier: Modifier, onResult: (String) -> Unit, onStatus: (ScannerStatus) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val resultCb by rememberUpdatedState(onResult)
    val statusCb by rememberUpdatedState(onStatus)
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var resumed by remember { mutableStateOf(true) }
    val textureView = remember { TextureView(context) }

    DisposableEffect(activity) {
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (a !== activity) return
                granted = hasCameraPermission(context)
                resumed = true
            }
            override fun onActivityPaused(a: Activity) { if (a === activity) resumed = false }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        activity?.registerActivityLifecycleCallbacks(cb)
        onDispose { activity?.unregisterActivityLifecycleCallbacks(cb) }
    }

    // The permission result is not delivered to us: look again every second until it is granted.
    LaunchedEffect(granted) {
        if (!granted) {
            statusCb(ScannerStatus.NO_PERMISSION)
            while (!hasCameraPermission(context)) delay(1000)
            granted = true
        }
    }

    DisposableEffect(granted, resumed) {
        var engine: Engine? = null
        if (granted && resumed) {
            engine = Engine(context, textureView, { statusCb(it) }, { resultCb(it) }).also { it.start() }
        }
        onDispose { engine?.dispose() }
    }

    Box(modifier = modifier) {
        AndroidView(modifier = Modifier.matchParentSize(), factory = { textureView })
    }
}

/** Owns the TextureView listeners and one [CamRun] at a time; main thread only. */
private class Engine(
    private val ctx: Context,
    private val tv: TextureView,
    private val onStatus: (ScannerStatus) -> Unit,
    private val onResult: (String) -> Unit
) {
    private var disposed = false
    private var run: CamRun? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = open()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { run?.applyTransform() }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeRun(); return true }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }
    private val layoutListener = LayoutWatcher { run?.applyTransform() }

    fun start() {
        onStatus(ScannerStatus.STARTING)
        tv.surfaceTextureListener = surfaceListener
        tv.addOnLayoutChangeListener(layoutListener)
        if (tv.isAvailable) open()
    }

    private fun open() {
        if (disposed || run != null) return
        run = CamRun(
            ctx, tv,
            { s -> if (!disposed) onStatus(s) },
            { t -> if (!disposed) onResult(t) }
        ).also { it.open() }
    }

    private fun closeRun() {
        run?.close()
        run = null
    }

    fun dispose() {
        disposed = true
        tv.surfaceTextureListener = null
        tv.removeOnLayoutChangeListener(layoutListener)
        closeRun()
    }
}

private class LayoutWatcher(private val f: () -> Unit) : android.view.View.OnLayoutChangeListener {
    override fun onLayoutChange(
        v: android.view.View?, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, orr: Int, ob: Int
    ) = f()
}

/** One attempt at running the camera: threads, camera, session, readers. [close] frees all of it. */
private class CamRun(
    private val ctx: Context,
    private val tv: TextureView,
    private val emit: (ScannerStatus) -> Unit,
    private val emitText: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val camThread = HandlerThread("paddock-qr-cam").also { it.start() }
    private val decThread = HandlerThread("paddock-qr-dec").also { it.start() }
    private val camHandler = Handler(camThread.looper)
    private val decHandler = Handler(decThread.looper)
    private val lock = Any()
    private val readerLock = Any()

    @Volatile private var closed = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewW = 0
    private var previewH = 0
    private var sensorOrientation = 90

    // Decode thread only.
    private val qr = QRCodeReader()
    private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        put(DecodeHintType.TRY_HARDER, true)
    }
    private var yData = ByteArray(0)
    private var lastDecode = 0L
    private var lastText: String? = null
    private var lastSeen = 0L

    fun open() {
        try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = mgr.cameraIdList
            if (ids.isEmpty()) return fail(ScannerStatus.NO_CAMERA)
            val id = ids.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: ids[0]
            val ch = mgr.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail(ScannerStatus.ERROR)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            val prev = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            // One size for preview and analysis, so what is seen is what is decoded.
            val size = yuv.filter { it in prev }.ifEmpty { yuv }
                .minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: return fail(ScannerStatus.ERROR)
            previewW = size.width
            previewH = size.height

            val st = tv.surfaceTexture ?: return fail(ScannerStatus.ERROR)
            st.setDefaultBufferSize(size.width, size.height)
            previewSurface = Surface(st)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also {
                it.setOnImageAvailableListener({ r -> onImage(r) }, decHandler)
            }
            applyTransform()
            mgr.openCamera(id, stateCallback, camHandler)
        } catch (e: SecurityException) {
            fail(ScannerStatus.NO_PERMISSION)
        } catch (e: Exception) {
            fail(ScannerStatus.ERROR)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
            synchronized(lock) {
                if (closed) { cam.close(); return }
                camera = cam
            }
            try {
                val outputs = listOf(
                    OutputConfiguration(previewSurface!!),
                    OutputConfiguration(reader!!.surface)
                )
                val exec = Executor { camHandler.post(it) }
                cam.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec, sessionCallback)
                )
            } catch (e: Exception) {
                fail(ScannerStatus.ERROR)
            }
        }
        override fun onDisconnected(cam: CameraDevice) = fail(ScannerStatus.ERROR)
        override fun onError(cam: CameraDevice, error: Int) = fail(ScannerStatus.ERROR)
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            val cam: CameraDevice
            synchronized(lock) {
                if (closed) { s.close(); return }
                session = s
                cam = camera ?: return
            }
            try {
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(previewSurface!!)
                b.addTarget(reader!!.surface)
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                s.setRepeatingRequest(b.build(), null, camHandler)
                main.post { if (!closed) emit(ScannerStatus.SCANNING) }
            } catch (e: Exception) {
                fail(ScannerStatus.ERROR)
            }
        }
        override fun onConfigureFailed(s: CameraCaptureSession) = fail(ScannerStatus.ERROR)
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val w = img.width
        val h = img.height
        var stride = 0
        try {
            synchronized(readerLock) {
                if (closed) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastDecode < 160) return // ~6 decodes per second
                lastDecode = now
                val plane = img.planes[0]
                val buf = plane.buffer
                if (yData.size != buf.remaining()) yData = ByteArray(buf.remaining())
                buf.get(yData)
                stride = plane.rowStride
            }
        } catch (e: Exception) {
            return
        } finally {
            img.close()
        }
        val text = try {
            val src = PlanarYUVLuminanceSource(yData, stride, h, 0, 0, w, h, false)
            qr.decode(BinaryBitmap(HybridBinarizer(src)), hints).text
        } catch (e: ReaderException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            qr.reset()
        }
        if (text.isNullOrEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val repeat = text == lastText && now - lastSeen < 2000
        lastText = text
        lastSeen = now
        if (!repeat) main.post { if (!closed) emitText(text) }
    }

    /** Centre-crops the preview into the view and turns it upright for the display rotation. */
    fun applyTransform() {
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw <= 0f || vh <= 0f || previewW == 0) return
        // The camera delivers the picture upright for the device's natural orientation, stretched
        // over the whole view. Undo the stretch, rotate for the display, then scale to cover.
        val swap = sensorOrientation % 180 != 0
        val cw = (if (swap) previewH else previewW).toFloat()
        val chh = (if (swap) previewW else previewH).toFloat()
        val rot = try { ctx.display?.rotation } catch (e: Exception) { null } ?: Surface.ROTATION_0
        val angle = when (rot) {
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 90f
            else -> 0f
        }
        val sideways = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val dw = if (sideways) chh else cw
        val dh = if (sideways) cw else chh
        val k = max(vw / dw, vh / dh)
        val cx = vw / 2f
        val cy = vh / 2f
        val m = Matrix()
        m.setScale(cw / vw, chh / vh, cx, cy)
        m.postRotate(angle, cx, cy)
        m.postScale(k, k, cx, cy)
        tv.setTransform(m)
    }

    private fun fail(s: ScannerStatus) {
        val first = !closed
        close()
        if (first) main.post { emit(s) }
    }

    fun close() {
        val c: CameraDevice?
        val s: CameraCaptureSession?
        synchronized(lock) {
            if (closed) return
            closed = true
            c = camera
            s = session
            camera = null
            session = null
        }
        try { s?.close() } catch (e: Exception) {}
        try { c?.close() } catch (e: Exception) {}
        synchronized(readerLock) {
            try { reader?.setOnImageAvailableListener(null, null) } catch (e: Exception) {}
            try { reader?.close() } catch (e: Exception) {}
            reader = null
        }
        try { previewSurface?.release() } catch (e: Exception) {}
        previewSurface = null
        camThread.quitSafely()
        decThread.quitSafely()
    }
}
