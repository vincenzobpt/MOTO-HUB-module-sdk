// SPDX-License-Identifier: Apache-2.0
// The contract every MOTO-HUB module is loaded against - generic, capability-based, and ignorant
// of what any particular module does. The app ships these exact classes; a module compiles
// against them (compileOnly) and resolves them to the app's copy at runtime.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.motohub.android.module"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Named by the contract, so part of it. Same versions as the app, which is what makes the
    // module's references resolve to the app's classes.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.car.app)
}
