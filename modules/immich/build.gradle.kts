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
    id = "immich"
    version = "0.3.0"
    entryClass = "io.motohub.immich.ImmichEntry"
    // ModuleMapLayer (30); photo pins and navigateTo (31).
    contractVersion = 31
    displayName = "Immich"
    description = "Shows the photos of your own Immich server on the NAV map."
}

android {
    namespace = "io.motohub.immich"
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

}

dependencies {
    compileOnly(project(":module-api"))
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.foundation)

    // ExoPlayer plays the videos in place. The app ships no media3, so this copy is the module's
    // own: `implementation`, packed into the module's dex by moduleJar, as the Dashcam does.
    implementation("androidx.media3:media3-exoplayer:1.9.2")

    // android.jar's org.json is a stub that throws on the JVM, so the tests bring the real one.
    // The Compose compiler wants the runtime on the test classpath too.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.runtime)
    testImplementation(project(":module-api"))
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
