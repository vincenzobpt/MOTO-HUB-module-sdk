// SPDX-License-Identifier: AGPL-3.0-only
// Flyby Route Simulator, as published in MOTO-HUB: plans a route and rides it virtually, and
// hands the app a trip it plays like any other. AGPL-3.0; see NOTICE.md for the MIT-licensed
// parts of its simulation engine.
//
// Everything it needs besides its own code - kotlin-stdlib, Compose, coroutines, the module
// contract - is already in the app and reaches it through the class loader the app makes its
// parent. So every dependency is compileOnly.
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
    id = "flyby-route-simulator"
    version = published.getProperty("version")
    entryClass = "io.motohub.android.routesim.plugin.RouteSimulatorEntry"
    contractVersion = published.getProperty("contractVersion").toInt()
    displayName = "Flyby Route Simulator"
    description = "Plans a route and rides it virtually, producing a trip the app can play back."
}

android {
    namespace = "io.motohub.android.routesim"
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

    kotlinOptions {
        jvmTarget = "17"
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

    // At test time there is no app underneath to lend them. The plan store reads and writes JSON,
    // and android.jar's org.json is a stub that throws on the JVM, so the tests bring the real one.
    testImplementation(project(":module-api"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

/**
 * RouteSimulatorEntry states id, version and contract a second time, for the module's own
 * manifest. Two copies nobody compares go stale, so they are compared here.
 */
val checkModuleManifestAgrees by tasks.registering {
    val entry = file("src/main/java/io/motohub/android/routesim/plugin/RouteSimulatorEntry.kt")
    inputs.file(entry)
    outputs.upToDateWhen { false }
    doLast {
        val declared = entry.readText()
        listOf(
            "id" to "flyby-route-simulator",
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
