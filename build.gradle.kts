plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Paddock carries its own CPython (modules/paddock).
    id("com.chaquo.python") version "17.2.0" apply false
}
