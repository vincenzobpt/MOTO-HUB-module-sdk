// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
// of the MPL was not distributed with this file, you can obtain one at https://mozilla.org/MPL/2.0/.
//
// Paddock carries its own CPython (the Chaquopy runtime) with Reticulum and LXMF. The Chaquopy
// plugin builds them into this library's AAR; moduleJar is widened to carry the AAR's native
// libraries (lib/arm64-v8a, unpacked by the app, contract 39) and its Python files (as jar
// resources under paddock-py/, unpacked by the module itself - see engine/ModulePython.kt).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.chaquo.python")
    id("motohub.module")
}

motohubModule {
    id = "paddock"
    version = "0.5.13"
    entryClass = "io.motohub.paddock.PaddockEntry"
    // Native libraries (39). Map pins that move (refreshSeconds, 44) are read by an app at 44 or
    // later and ignored before it: the module still runs, the pins move when the map moves.
    contractVersion = 39
    displayName = "Paddock"
    description = "Ride together, privately: a QR to open a room, everyone on the NAV map. No account, no server."
}

android {
    namespace = "io.motohub.paddock"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        // MOTO-HUB itself ships arm64-v8a only.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

chaquopy {
    defaultConfig {
        version = "3.13"
        // The build machine's Python must match the version above.
        buildPython(providers.gradleProperty("paddock.buildPython").getOrElse("python3.13"))
        pip {
            install("rns==1.6.1")
            install("lxmf==1.3.1")
        }
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

    // QR codes. The app's own ZXing is renamed by R8, so this copy cannot collide with it.
    // shortcut: not relocated; if the app ever keeps ZXing's names, shade this into io.motohub.paddock.
    implementation("com.google.zxing:core:3.5.4")

    // android.jar's org.json is a stub that throws on the JVM, so the tests bring the real one.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.runtime)
    testImplementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":module-api"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.named<Zip>("moduleJar") {
    val aar = layout.buildDirectory.file("outputs/aar/paddock-release.aar")
    inputs.file(aar)
    from({ zipTree(aar.get().asFile) }) {
        include("jni/arm64-v8a/*.so")
        eachFile { path = "lib/arm64-v8a/$name" }
        includeEmptyDirs = false
    }
    from({ zipTree(aar.get().asFile) }) {
        include("assets/chaquopy/**")
        eachFile { path = "paddock-py/" + path.removePrefix("assets/") }
        includeEmptyDirs = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
