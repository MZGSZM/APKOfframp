plugins {
    alias(libs.plugins.android.application)
}

// The emulator workflow builds this twice: -PtestappVersion=1 and -PtestappVersion=2.
val testappVersion = providers.gradleProperty("testappVersion").orNull?.toInt() ?: 1

android {
    namespace = "com.mzgszm.offramptest"
    compileSdk = 37
    enableKotlin = false

    defaultConfig {
        applicationId = "com.mzgszm.offramptest"
        minSdk = 26
        targetSdk = 37
        versionCode = testappVersion
        versionName = "1.0.$testappVersion"
    }
}
