// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see LICENSE. Copy it freely as the start of your own module.
package com.example.hello

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.motohub.android.module.ModuleFeature
import io.motohub.android.module.ModuleFeaturePlacement
import io.motohub.android.module.ModuleFeatures
import io.motohub.android.module.ModuleManifest
import io.motohub.android.module.MotoHubModule
import io.motohub.android.module.MotoHubModuleEntry
import io.motohub.android.module.MotoHubModuleHost
import java.io.File

/**
 * The class named in `motohubModule.entryClass`. MOTO-HUB builds it reflectively, so it needs a
 * public no-argument constructor, and it is the only class of a module that is built that way.
 */
class HelloModuleEntry : MotoHubModuleEntry {
    override fun create(host: MotoHubModuleHost): MotoHubModule = HelloModule(host)
}

private class HelloModule(private val host: MotoHubModuleHost) : MotoHubModule {

    // Must agree with motohubModule { } in build.gradle.kts: the app reads that copy from the file
    // before loading anything, this one is what the module reports once it runs.
    override val manifest = ModuleManifest(
        id = "hello",
        version = "0.1.0",
        contractVersion = 10,
        entryClass = "com.example.hello.HelloModuleEntry",
        displayName = "Hello module",
        description = "The smallest useful MOTO-HUB module: one page, one button."
    )

    private val features = HelloFeatures(host)

    init {
        // Goes to MOTO-HUB's own event log, so it appears in a diagnostics report.
        host.log.log("Hello module loaded.")
    }

    // The whole of how the app reaches a module: it asks for an interface it ships, by type, and
    // gets an implementation or null. Answer only what you implement.
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> capability(type: Class<T>): T? = when (type) {
        ModuleFeatures::class.java -> features as T
        else -> null
    }

    override fun release() {
        host.log.log("Hello module released.")
    }
}

private class HelloFeatures(private val host: MotoHubModuleHost) : ModuleFeatures {

    /** The module's own directory: survives app updates, removed with the module. */
    private val counterFile = File(host.storage, "presses.txt")

    override fun features(): List<ModuleFeature> = listOf(
        ModuleFeature(
            id = "hello-about",
            title = "Hello module",
            description = "What it is and a button to press",
            // MODULES = the page behind the module's card, under Modules.
            placement = ModuleFeaturePlacement.MODULES,
            screen = { onBack -> AboutScreen(onBack) }
        )
    )

    @Composable
    private fun AboutScreen(onBack: () -> Unit) {
        var presses by remember { mutableStateOf(readPresses()) }
        // host.ui draws with the app's own components, so the page looks like the rest of MOTO-HUB.
        host.ui.Screen("Hello module", onBack) {
            host.ui.Paragraph(
                "This page comes from a module: a file MOTO-HUB downloaded and loaded while it " +
                    "was running. Nothing about it is compiled into the app."
            )
            host.ui.SectionLabel("STATE")
            host.ui.Fact("Button pressed", presses.toString(), false)
            host.ui.Fact("Files", host.storage.absolutePath, true)
            host.ui.PrimaryButton("Press", true) {
                presses += 1
                counterFile.writeText(presses.toString())
                host.log.log("Hello button pressed ($presses).")
            }
        }
    }

    private fun readPresses(): Int =
        counterFile.takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull() ?: 0
}
