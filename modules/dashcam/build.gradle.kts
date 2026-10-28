// SPDX-License-Identifier: AGPL-3.0-only
// Dashcam, as published in MOTO-HUB: joins a Wi-Fi dashcam's own access point and turns the
// phone into its screen - the live picture, the camera's buttons and settings, the files on its
// card - and puts the camera in the dashboard's map panel. AGPL-3.0.
//
// Everything it needs besides its own code - kotlin-stdlib, Compose, coroutines, the module
// contract - is already in the app and reaches it through the class loader the app makes its
// parent, so those are compileOnly. ExoPlayer is the one library the app does not ship: it is
// `implementation`, dexed into the module by moduleJar.
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("motohub.module")
}

// Version and contract of the published module, written by the export that produced this copy.
val published = Properties().apply { file("module.properties").inputStream().use(::load) }

motohubModule {
    id = "dashcam"
    version = published.getProperty("version")
    entryClass = "io.motohub.android.dashcam.plugin.DashcamEntry"
    contractVersion = published.getProperty("contractVersion").toInt()
    displayName = "Dashcam"
    description = "Shows a Wi-Fi dashcam full-screen on the phone, with its buttons, settings and files."
}

android {
    namespace = "io.motohub.android.dashcam.module"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    buildFeatures {
        aidl = false
        buildConfig = false
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    compileOnly(project(":module-api"))
    compileOnly(libs.kotlinx.coroutines.android)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.foundation)

    // ExoPlayer plays the camera's recordings: Android's MediaPlayer reads a source synchronously,
    // and every wait on the camera's Wi-Fi became a stall in a 4K film. The app ships no media3,
    // so this one is the module's own, with the Guava it needs.
    implementation("androidx.media3:media3-exoplayer:1.9.2")

    // The protocol parsers and the RTP depacketiser are plain Kotlin; their tests need the
    // contract's classes and org.json at test time, when there is no app underneath to lend them.
    testImplementation(project(":module-api"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

/**
 * DashcamEntry states id, version and contract a second time, for the module's own manifest.
 * Two copies nobody compares go stale, so they are compared here.
 */
val checkModuleManifestAgrees by tasks.registering {
    val entry = file("src/main/java/io/motohub/android/dashcam/plugin/DashcamEntry.kt")
    inputs.file(entry)
    outputs.upToDateWhen { false }
    doLast {
        val declared = entry.readText()
        listOf(
            "id" to "dashcam",
            "version" to published.getProperty("version"),
            "contractVersion" to published.getProperty("contractVersion")
        ).forEach { (field, expected) ->
            val quoted = field != "contractVersion"
            val pattern = if (quoted) """\b$field\s*=\s*"([^"]*)"""" else """\b$field\s*=\s*(\d+)"""
            val found = Regex(pattern).find(declared)?.groupValues?.get(1)
            check(found == expected) { "$entry says $field = \"$found\", module.properties says \"$expected\"." }
        }
    }
}
tasks.named("moduleManifest") { dependsOn(checkModuleManifestAgrees) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
