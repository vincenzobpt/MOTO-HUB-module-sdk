// A module is an Android library the app never links: it is dexed on its own, packed with a
// manifest, and loaded at runtime into MOTO-HUB's process. Copy this directory to start yours.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("motohub.module")
}

motohubModule {
    id = "hello"
    version = "0.1.0"
    entryClass = "com.example.hello.HelloModuleEntry"
    // MotoHubModuleContract.CONTRACT_VERSION of the module-api in this repository.
    contractVersion = 10
    displayName = "Hello module"
    description = "The smallest useful MOTO-HUB module: one page, one button."
}

android {
    namespace = "com.example.hello"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    buildFeatures {
        compose = true
        buildConfig = false
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
    // Everything compileOnly. The app lends these at runtime through the class loader it makes
    // the module's parent; bundling a copy would load a second version of a class MOTO-HUB
    // already has. See docs/borrowed-libraries.md.
    compileOnly(project(":module-api"))
    compileOnly(libs.kotlinx.coroutines.android)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
}
