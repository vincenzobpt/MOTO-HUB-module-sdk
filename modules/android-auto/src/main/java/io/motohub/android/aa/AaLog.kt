// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import android.util.Log

/**
 * Logging shim for the ported Android Auto (AAP) receiver.
 *
 * Mirrors the small subset of headunit-revived's `AppLog` API used by the ported files
 * (printf-style i/d/w/v/e), so those files port with minimal edits. Every line is also
 * forwarded to [sink] — wired to MOTO-HUB's on-screen log — prefixed
 * with the `[AA]` stage tag per the project logging convention.
 *
 * ### Why this file calls no stdlib helper it does not have to
 *
 * This module is dexed on its own and linked, at runtime, against the *app's* kotlin-stdlib
 * (see the module's build script: every dependency is `compileOnly`). The app is minified, and
 * `proguard-rules.pro` keeps the stdlib by NAME only, so R8 removes any member the app's own
 * code never calls — the module's calls are invisible to it.
 *
 * `e()` read the trailing vararg with `args.lastOrNull()`, which compiles to
 * `kotlin.collections.ArraysKt.lastOrNull([Ljava/lang/Object;)`. Nothing in the app calls it, so
 * it was not in the APK: in the shipped 0.1.20 build `ArraysKt` has 23 methods and not one is
 * named `last*`. Every AA error the receiver tried to write therefore threw `NoSuchMethodError`
 * on the transport's poll thread and killed the process — mid-ride, on a background thread, with
 * the phone in the rider's pocket (support 13274a94 and six other installations, 2026-09-20).
 *
 * The logger is the last place that may fail: it runs precisely when something else already has.
 * So the error path below uses array indexing and a StringBuilder — language constructs, not
 * library calls — and cannot be shrunk out from under it. `checkModuleLinkage` in the app's build
 * now fails the release if any *other* borrowed call goes the same way; this file does not rely on
 * that check to stay alive.
 */
object AaLog {
    const val TAG = "MotoHubAA"

    /** Flip to true for very chatty per-message logging during bring-up debugging. */
    @Volatile var LOG_VERBOSE = false

    /** Routes AA logs into the app's on-screen event log. */
    @Volatile var sink: ((String) -> Unit)? = null

    private fun fmt(msg: String, args: Array<out Any?>, count: Int = args.size): String {
        if (count == 0) return msg
        return try {
            // Copied by hand rather than with copyOfRange(): same reason as lastOrNull() above,
            // and the error path must not depend on a second borrowed helper surviving R8.
            val slice = arrayOfNulls<Any?>(count)
            var i = 0
            while (i < count) {
                slice[i] = args[i]
                i++
            }
            String.format(msg, *slice)
        } catch (e: Exception) {
            val out = StringBuilder(msg)
            var i = 0
            while (i < count) {
                out.append(' ').append(args[i].toString())
                i++
            }
            out.toString()
        }
    }

    private fun emit(msg: String) {
        Log.i(TAG, msg)
        try { sink?.invoke("[AA] $msg") } catch (_: Exception) {}
    }

    fun i(msg: String, vararg args: Any?) = emit(fmt(msg, args))
    fun d(msg: String, vararg args: Any?) = emit(fmt(msg, args))
    fun v(msg: String, vararg args: Any?) { if (LOG_VERBOSE) emit(fmt(msg, args)) }
    fun w(msg: String, vararg args: Any?) = emit("W: " + fmt(msg, args))

    fun e(msg: String, vararg args: Any?) {
        // If a Throwable was passed as the trailing arg (AppLog.e("msg", exception) style),
        // append its message/stack rather than feeding it to String.format.
        val last = if (args.isEmpty()) null else args[args.size - 1]
        val tr = last as? Throwable
        if (tr != null) {
            emit("E: " + fmt(msg, args, args.size - 1) + " :: " + Log.getStackTraceString(tr))
        } else {
            emit("E: " + fmt(msg, args))
        }
    }

    fun e(tr: Throwable) = emit("E: " + Log.getStackTraceString(tr))
}
