plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    id("com.android.library") version "9.5.0" apply false
    id("com.android.application") version "9.5.0" apply false
    alias(libs.plugins.kotlinAndroid) apply false
}
