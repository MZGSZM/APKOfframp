// AGP 9 ships built-in Kotlin with an older bundled KGP. Putting the Kotlin
// Gradle plugin on the build classpath here makes it use the catalog version.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
