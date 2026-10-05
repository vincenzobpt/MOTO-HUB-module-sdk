// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.motohub.android.dashcam.DashcamController
import io.motohub.android.dashcam.protocol.CameraFile
import io.motohub.android.module.ModuleUi
import io.motohub.android.module.MotoHubModuleHost
import java.io.File
import kotlinx.coroutines.delay

/** One screen of the module. The app gives a module one page; the module walks its own screens inside it. */
internal sealed class Page {
    object Home : Page()
    object Wifi : Page()
    object Settings : Page()
    object Live : Page()
    class Files(val local: Boolean) : Page()
    class Player(val camera: CameraFile?, val local: File?) : Page()

    /** Live, files and the player fill a landscape screen; the setup pages follow the phone. */
    val landscape: Boolean get() = this is Live || this is Files || this is Player
}

/**
 * The whole module UI, from [start]. Back walks back through the screens the rider opened and
 * leaves the module only from the first: the app keeps one module page at a time, so the stack of
 * screens is the module's own.
 */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
fun DashcamApp(host: MotoHubModuleHost, controller: DashcamController, startLive: Boolean, onExit: () -> Unit) {
    var stack by remember { mutableStateOf<List<Page>>(listOf(if (startLive) Page.Live else Page.Home)) }
    val page = stack[stack.size - 1]
    val open: (Page) -> Unit = { next -> stack = ArrayList(stack).apply { add(next) } }
    val back: () -> Unit = {
        if (stack.size > 1) stack = ArrayList(stack.subList(0, stack.size - 1)) else onExit()
    }
    val notice by controller.notice.collectAsState()

    DisposableEffect(Unit) {
        controller.attach()
        onDispose { controller.detach() }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3500)
            controller.clearNotice()
        }
    }

    host.ui.Backdrop(back) {
        WindowMode(page.landscape)
        Box(Modifier.fillMaxSize().background(Dc.bg)) {
            when (page) {
                Page.Home -> HomePage(controller, open, back)
                Page.Wifi -> WifiPage(controller, back)
                Page.Settings -> SettingsPage(controller, back)
                Page.Live -> LivePage(controller, open, back)
                is Page.Files -> FilesPage(controller, page.local, open, back)
                is Page.Player -> PlayerPage(controller, page.camera, page.local, back)
            }
            notice?.let { text ->
                Box(Modifier.fillMaxSize().padding(top = 76.dp), contentAlignment = Alignment.TopCenter) {
                    Text(
                        text, color = Dc.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Dc.surface).padding(horizontal = 18.dp, vertical = 11.dp)
                    )
                }
            }
        }
    }
}

/** Landscape, no system bars, the screen kept on - for the screens on video; undone when they close. */
@Composable
@ComposableTarget(ModuleUi.UI_APPLIER)
private fun WindowMode(landscape: Boolean) {
    val view = LocalView.current
    DisposableEffect(landscape) {
        val activity = view.context.findActivity()
        val window = activity?.window
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (landscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.decorView?.windowInsetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            if (landscape) {
                activity?.requestedOrientation = previous
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window?.decorView?.windowInsetsController?.show(WindowInsets.Type.systemBars())
            }
        }
    }
    // Opened from a tap on the dashboard's panel, the dashboard's own clean-up shows the bars again
    // after they were hidden here, and Android's clock and icons land on the title. Keep them hidden
    // while the video is up; a swipe still peeks them, and transient bars do not count as shown.
    LaunchedEffect(landscape) {
        if (!landscape) return@LaunchedEffect
        while (true) {
            delay(BARS_CHECK_MS)
            val insets = view.rootWindowInsets ?: continue
            if (insets.isVisible(WindowInsets.Type.statusBars()) || insets.isVisible(WindowInsets.Type.navigationBars())) {
                view.context.findActivity()?.window?.decorView?.windowInsetsController?.hide(WindowInsets.Type.systemBars())
            }
        }
    }
}

private const val BARS_CHECK_MS = 400L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
