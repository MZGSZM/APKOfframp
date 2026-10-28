plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The release workflow passes -PofframpVersion=1.2.3 from the git tag.
val offrampVersion: String = providers.gradleProperty("offrampVersion").orNull ?: "0.1.0"

fun versionCodeOf(name: String): Int {
    val parts = name.removePrefix("v").substringBefore('-').split('.')
    require(parts.size == 3) { "Version must look like 1.2.3, got $name" }
    val (major, minor, patch) = parts.map { it.toInt() }
    require(minor < 1000 && patch < 1000) { "Minor and patch must stay below 1000" }
    return major * 1_000_000 + minor * 1_000 + patch
}

// Release signing comes from environment variables set by the release workflow.
// Without them the release build is left unsigned, which is fine for CI.
val keystorePath: String? = System.getenv("OFFRAMP_KEYSTORE")
val hasSigning = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

android {
    namespace = "com.mzgszm.apkofframp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mzgszm.apkofframp"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeOf(offrampVersion)
        versionName = offrampVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("OFFRAMP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OFFRAMP_KEY_ALIAS")
                keyPassword = System.getenv("OFFRAMP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Unit tests inherit the environment, so COPYPARTY_PYTHON and REQUIRE_COPYPARTY reach the copyparty tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
