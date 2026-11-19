// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 sur00t (https://github.com/sur00t), who wrote the WT9011 module.
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("motohub.module")
}

motohubModule {
    id = "wt9011"
    version = "0.2.0"
    entryClass = "io.github.sur00t.wt9011.Wt9011Entry"
    // ModuleMotionSource, which hands every sample to MOTO-HUB, arrived with contract 43.
    contractVersion = 43
    displayName = "WT9011 IMU"
    description = "Lean, g-forces and heading from a WitMotion WT9011DCL over Bluetooth LE."
}

android {
    namespace = "io.github.sur00t.wt9011"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
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

dependencies {
    compileOnly(project(":module-api"))
    compileOnly(libs.kotlinx.coroutines.android)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)

    // The Compose compiler wants the runtime on the test classpath too.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.runtime)
    testImplementation(project(":module-api"))
    testImplementation(libs.junit)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
