// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.dawarich

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Starts, stops and uploads the official Dawarich app's recording through the Tasker plugin it
 * ships, the way Tasker does: bound to the plugin's service, which is handed the action's runner
 * class. The app answers through the completion intent we hand it.
 *
 * Bound, not broadcast: a broadcast wakes the app in the background, where Android refuses it the
 * foreground service recording needs. While a foreground app (MOTO-HUB) holds a binding to it,
 * Android counts the Dawarich app as in the foreground too, and the start goes through.
 *
 * The rider has to allow it once in the Dawarich app (Settings > Android automation); until then
 * every command comes back as error 1007, which [lastResult] says in the app's own words.
 */
class DawarichApp(private val context: Context, private val log: (String) -> Unit) {

    enum class Command(val runner: String, val done: String) {
        START("app.dawarich.Dawarich.tasker.StartTrackingActionRunner", "Recording started"),
        STOP("app.dawarich.Dawarich.tasker.StopTrackingActionRunner", "Recording stopped"),
        UPLOAD("app.dawarich.Dawarich.tasker.UploadTrackActionRunner", "Upload started")
    }

    @Volatile var lastResult = "Nothing sent yet"
    /** Called with the command and whether the app accepted it. */
    @Volatile var onResult: (Command, Boolean) -> Unit = { _, _ -> }

    private var receiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    // One binding per command in flight, let go when the app answers or after a while.
    private val bindings = HashMap<Command, ServiceConnection>()

    fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Opens the Dawarich page where the rider allows other apps to drive it. */
    fun openAutomationSettings() {
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("dawarich://settings/android-automation"))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(open)
        } catch (e: Exception) {
            lastResult = "Could not open the Dawarich app: ${e.message}"
        }
    }

    fun send(command: Command) {
        listen()
        val reply = Intent(ACTION_RESULT).setPackage(context.packageName).putExtra(EXTRA_COMMAND, command.name)
        val plugin = Bundle().apply {
            putString(EXTRA_RUNNER_CLASS, command.runner)
            putString(EXTRA_INPUT_CLASS, "kotlin.Unit")
        }
        val run = Intent(ACTION_FIRE_SETTING)
            .setComponent(ComponentName(PACKAGE, SERVICE))
            .putExtra(EXTRA_BUNDLE, plugin)
            .putExtra(EXTRA_COMPLETION_INTENT, reply.toUri(Intent.URI_INTENT_SCHEME))
            // Bound, the plugin's service needs no notification of its own.
            .putExtra(EXTRA_CAN_BIND_FIRE_SETTING, true)
        main.post {
            unbind(command)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    try {
                        context.startService(run)
                    } catch (e: Exception) {
                        lastResult = "The Dawarich app refused the command: ${e.message}"
                        unbind(command)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
            val bound = try {
                context.bindService(run, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                false
            }
            if (!bound) {
                lastResult = "Could not reach the Dawarich app. Is it installed?"
                runCatching { context.unbindService(connection) }
                return@post
            }
            bindings[command] = connection
            main.postDelayed({ unbind(command) }, HOLD_MS)
            lastResult = "Asked: ${command.name.lowercase()}…"
            log("Dawarich app: sent ${command.name}")
        }
    }

    private fun unbind(command: Command) {
        bindings.remove(command)?.let { runCatching { context.unbindService(it) } }
    }

    fun release() {
        main.post { Command.entries.forEach(::unbind) }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    @Synchronized
    private fun listen() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val command = intent.getStringExtra(EXTRA_COMMAND)
                    ?.let { name -> Command.entries.firstOrNull { it.name == name } } ?: return
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_UNKNOWN)
                val ok = code == RESULT_OK || code == RESULT_OK_MINOR_FAILURES
                lastResult = if (ok) command.done else describeError(intent.getBundleExtra(EXTRA_VARIABLES))
                log("Dawarich app: ${command.name} -> $code")
                main.post { unbind(command) }
                onResult(command, ok)
            }
        }
        // Exported: the answer comes from the Dawarich app's process.
        context.registerReceiver(r, IntentFilter(ACTION_RESULT), Context.RECEIVER_EXPORTED)
        receiver = r
    }

    private companion object {
        const val PACKAGE = "app.dawarich.Dawarich"
        const val SERVICE = "com.joaomgcd.taskerpluginlibrary.action.IntentServiceAction"
        const val EXTRA_CAN_BIND_FIRE_SETTING = "net.dinglisch.android.tasker.EXTRA_CAN_BIND_FIRE_SETTING"
        const val HOLD_MS = 30_000L
        const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
        const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
        const val EXTRA_RUNNER_CLASS = "net.dinglisch.android.tasker.extras.ACTION_RUNNER_CLASS"
        const val EXTRA_INPUT_CLASS = "net.dinglisch.android.tasker.extras.ACTION_INPUT_CLASS"
        const val EXTRA_COMPLETION_INTENT = "net.dinglisch.android.tasker.extras.COMPLETION_INTENT"
        const val EXTRA_RESULT_CODE = "net.dinglisch.android.tasker.extras.RESULT_CODE"
        const val EXTRA_VARIABLES = "net.dinglisch.android.tasker.extras.VARIABLES"
        const val RESULT_OK = -1
        const val RESULT_OK_MINOR_FAILURES = 1
        const val RESULT_UNKNOWN = 4
        const val ACTION_RESULT = "io.motohub.dawarich.APP_RESULT"
        const val EXTRA_COMMAND = "command"
    }
}

/** A failure comes back as result 2 with the app's code in %err and its message in %errmsg. */
internal fun describeError(variables: Bundle?): String =
    variables?.getString("%errmsg")?.takeIf { it.isNotBlank() }
        ?: describeErrorCode(variables?.getString("%err")?.toIntOrNull() ?: 0)

internal fun describeErrorCode(code: Int): String = when (code) {
    1001, 1002 -> "The Dawarich app has no location permission: open it and allow location all the time."
    1003 -> "Android did not let the Dawarich app start recording in the background: open it once."
    1007 -> "Automation is off in the Dawarich app: turn it on in its Settings > Android automation."
    0 -> "The Dawarich app did not say why it refused."
    else -> "The Dawarich app answered with error $code."
}
