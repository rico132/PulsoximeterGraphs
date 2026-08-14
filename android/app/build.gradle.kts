plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.oxipulse.pulsoximetergraphs"
    // Latest stable platform whose toolchain floor (AGP/Gradle/JDK) this project already
    // satisfies. Verified against AGP 8.13.x's own documented max-compileSdk (36.1) —
    // see gradle/libs.versions.toml for the full version-selection notes.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oxipulse.pulsoximetergraphs"
        // GrapheneOS only runs on Pixel 6+, which shipped on Android 12 (API 31) — no
        // realistic sub-31 install base, and it drops all legacy Bluetooth-permission
        // branches (pre-S apps needed ACCESS_FINE_LOCATION for BLE scanning).
        minSdk = 31
        targetSdk = 36
        // Overridable via -PappVersionCode=/-PappVersionName= (the release workflow passes
        // these so the built APK's version matches the GitHub release tag); these literals
        // are just the fallback for local/manual builds that don't pass them.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    // Release builds must be reproducibly signed with the *same* certificate build-over-build —
    // otherwise Android refuses to install an update over whatever's already on the device
    // ("signatures do not match", surfaced by installers like Obtainium as a package conflict).
    // The release workflow used to build assembleDebug, whose keystore is auto-generated fresh
    // by AGP on every CI run (GitHub Actions runners have no persisted ~/.android/debug.keystore),
    // so every past release was signed with a different, throwaway key. A real release keystore
    // is decoded from a GitHub Actions secret into RELEASE_KEYSTORE_PATH by the workflow; when
    // that env var isn't set (e.g. local `./gradlew assembleRelease`), release stays unsigned —
    // same as before this was introduced.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    val hasReleaseSigning = releaseKeystorePath != null && file(releaseKeystorePath).exists()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    // The old `android { kotlinOptions { jvmTarget = ... } }` DSL is a hard compile error
    // as of this Kotlin Gradle plugin version — `compilerOptions` is the replacement.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
