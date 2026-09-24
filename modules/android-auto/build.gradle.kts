// SPDX-License-Identifier: AGPL-3.0-only
// The Android Auto module, as published in MOTO-HUB. AGPL-3.0: it is derived from
// headunit-revived; see LICENSE and NOTICE in this directory.
//
// Everything it needs besides its own code - kotlin-stdlib, protobuf, conscrypt, Compose, the
// module contract - is already in the app and reaches it through the class loader the app makes
// its parent. So every dependency is compileOnly.
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
    id = "android-auto"
    version = published.getProperty("version")
    entryClass = "io.motohub.android.aa.plugin.AaPluginEntry"
    contractVersion = published.getProperty("contractVersion").toInt()
    displayName = "Android Auto"
    description = "Runs Android Auto on the motorcycle's screen."
}

android {
    namespace = "io.motohub.android.aa.module"
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
    compileOnly(libs.protobuf.java)
    compileOnly(libs.conscrypt.android)
    compileOnly(libs.kotlinx.coroutines.android)
    compileOnly(platform(libs.androidx.compose.bom))
    compileOnly(libs.androidx.compose.runtime)
    compileOnly(libs.androidx.compose.ui)
    compileOnly(libs.androidx.compose.material3)
    compileOnly(libs.androidx.compose.foundation)

    // At test time there is no app underneath to lend them.
    testImplementation(project(":module-api"))
    testImplementation(libs.protobuf.java)
    testImplementation(libs.junit)
}

/**
 * AaPluginEntry states id, version and contract a second time, for the module's own manifest.
 * Two copies nobody compares go stale, so they are compared here.
 */
val checkModuleManifestAgrees by tasks.registering {
    val entry = file("src/main/java/io/motohub/android/aa/plugin/AaPluginEntry.kt")
    inputs.file(entry)
    outputs.upToDateWhen { false }
    doLast {
        val declared = entry.readText()
        listOf(
            "id" to "android-auto",
            "version" to published.getProperty("version"),
            "contractVersion" to published.getProperty("contractVersion")
        ).forEach { (field, expected) ->
            val quoted = field != "contractVersion"
            val pattern = if (quoted) """$field\s*=\s*"([^"]*)"""" else """$field\s*=\s*(\d+)"""
            val found = Regex(pattern).find(declared)?.groupValues?.get(1)
            check(found == expected) { "$entry says $field = \"$found\", module.properties says \"$expected\"." }
        }
    }
}
tasks.named("moduleManifest") { dependsOn(checkModuleManifestAgrees) }

/**
 * The head-unit identity Android Auto is shown is NOT part of this SDK. A module built from here
 * without one loads, but cannot present itself to Android Auto (AaReceiver.start says so). If you
 * have your own identity, put `aa_cert` and `aa_identity_data` in `identity/` (gitignored) and it
 * is packed where ModuleIdentity reads it.
 */
tasks.named<Zip>("moduleJar") {
    from(file("identity")) {
        include("aa_cert", "aa_identity_data")
        into("motohub")
    }
}
