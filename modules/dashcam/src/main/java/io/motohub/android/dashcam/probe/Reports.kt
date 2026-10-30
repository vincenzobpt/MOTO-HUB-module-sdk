// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.probe

import java.lang.reflect.Method

/**
 * The app's diagnostics report (contract 38's ModuleDiagnosticReports), reached by name.
 *
 * By name, not by type, because this module also loads into apps older than 38: there the type
 * does not exist, and naming it anywhere in the module's code would fail to resolve - and would
 * count as a missing reference in every linkage check against those apps. [of] returns null on
 * them, and the pages say how to send a report by hand instead.
 */
class Reports private constructor(
    private val host: Any,
    private val canSendGetter: Method,
    private val supportIdMethod: Method,
    private val sendMethod: Method
) {
    /** False when the app has no collector to send to. */
    val canSend: Boolean get() = runCatching { canSendGetter.invoke(host) as Boolean }.getOrDefault(false)

    /** The rider's Support ID, or empty when the app could not say. */
    fun supportId(): String = runCatching { supportIdMethod.invoke(host) as String }.getOrDefault("")

    /** Sends the report; [done] hears whether the first attempt reached the collector. */
    fun send(reason: String, done: (sent: Boolean) -> Unit) {
        val answer: (Boolean) -> Unit = { sent -> done(sent) }
        try {
            sendMethod.invoke(host, reason, answer)
        } catch (_: Exception) {
            done(false)
        }
    }

    companion object {
        const val INTERFACE = "io.motohub.android.module.ModuleDiagnosticReports"

        /** The app's reports behind [host], or null when the app is older than contract 38. */
        fun of(host: Any): Reports? = runCatching {
            val type = Class.forName(INTERFACE, false, host.javaClass.classLoader)
            if (!type.isInstance(host)) return null
            Reports(
                host,
                type.getMethod("getCanSendReports"),
                type.getMethod("supportId"),
                type.getMethod("sendReport", String::class.java, Function1::class.java)
            )
        }.getOrNull()
    }
}
