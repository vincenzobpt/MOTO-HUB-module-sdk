// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Vincenzo Buonomano.
// Part of the MOTO-HUB module SDK; see LICENSE.

// The `motohub.module` plugin: everything that turns an Android library into a file MOTO-HUB can
// load - manifest, dex, developer signature, the .mhm package, and the linkage check against a
// released APK. It touches no Android Gradle Plugin API, only task names, so it works with
// whatever AGP version the module applies itself.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

gradlePlugin {
    plugins {
        register("motohubModule") {
            id = "motohub.module"
            implementationClass = "motohub.MotoHubModulePlugin"
        }
    }
}
