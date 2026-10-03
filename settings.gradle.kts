pluginManagement {
    // The motohub.module plugin: manifest, dex, developer signature, .mhm, linkage check.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MOTO-HUB module SDK"

include(":module-api")
include(":examples:hello-module")
include(":modules:android-auto")
include(":modules:flyby-route-simulator")
// Your own module: copy examples/hello-module to modules/<name> and include it here.
