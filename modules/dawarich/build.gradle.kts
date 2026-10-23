// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("motohub.module")
}

motohubModule {
    id = "dawarich"
    version = "0.6.1"
    entryClass = "io.motohub.dawarich.DawarichEntry"
    // RIDE_ACTION (contract 14), host.rides.track and the live pill (ModuleLiveStatus, contract 27).
    contractVersion = 27
    displayName = "Dawarich"
    description = "Sends your position to your own Dawarich server while you ride."
}

android {
    namespace = "io.motohub.dawarich"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    // Code shared by the modules in this repository (modules/common): compiled in, not borrowed.
    sourceSets["main"].java.srcDir("../common/src/main/java")

    buildFeatures {
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
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.foundation)

    // android.jar's org.json is a stub that throws on the JVM, so the tests bring the real one.
    // The Compose compiler wants the runtime on the test classpath too.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.runtime)
    testImplementation(project(":module-api"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
