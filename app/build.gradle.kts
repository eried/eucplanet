import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "com.eried.eucplanet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eried.eucplanet"
        minSdk = 29
        targetSdk = 35
        versionCode = 222
        versionName = "0.8.11"

        val buildStamp = SimpleDateFormat("yyMMdd.HHmm")
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")

        // Current git branch, baked in at build time so the About dialog can
        // show which branch a build came from. Empty when git isn't available;
        // the UI hides the tag for "main" / detached HEAD.
        val gitBranch = try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").start()
            val out = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            if (process.exitValue() == 0) out else ""
        } catch (e: Exception) { "" }
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Wear companion embedding: scoped to the release variant of the Play-Store
// build and toggleable via -PembedWear=false. The wear module is a non-standalone
// companion (com.google.android.wearable.standalone="false" in wear/AndroidManifest.xml),
// so it has to ride inside the phone AAB for Google Play to auto-deliver it to a
// paired Wear OS device. Debug builds and the GitHub-sideload APK don't need that
// auto-delivery path, so we keep them slim (60 MB -> 20 MB phone APK).
//
// Note: `wearApp` is a legacy AGP DSL (Wear OS 1.x era). On our AGP (8.7.3) it
// works silently with no deprecation warning. AGP 8.x through 8.13 keeps it.
// **AGP 9.0.1 (January 2026) removes the DSL entirely** ("AGP 9.0 removes support
// for embedding Wear OS apps, which is no longer supported in Play"), so any
// upgrade to 9.x will break this build. See:
//   https://developer.android.com/build/releases/agp-9-0-0-release-notes
// Migration path when forced off 8.x: switch to Play Multi-APK delivery, drop
// this line, ship `wear-release.aab` as a separate AAB on the same applicationId
// to the Wear OS form-factor track in Play Console. Same end-user behaviour
// (Play auto-installs the watch app on paired devices when the phone app
// installs), just packaged as two AABs in one release instead of one embedded.
val embedWear = (project.findProperty("embedWear") as? String)?.lowercase() != "false"

dependencies {
    if (embedWear) {
        "releaseWearApp"(project(":wear"))
    }

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)

    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore (settings live here as a single JSON blob so schema bumps
    // never wipe rider configuration. Room is reserved for trips, alarms
    // and per-wheel profiles, which get real migrations.)
    implementation(libs.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Location
    implementation(libs.play.services.location)

    // MapLibre - TODO: re-enable when map preview is implemented
    // Disabled for now to avoid 16KB page size compatibility warning on Android 15+
    // implementation(libs.maplibre)

    // Flic 2 button SDK
    implementation(libs.flic2)

    // WorkManager (background trip upload)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Drag-to-reorder for settings lists
    implementation(libs.reorderable)

    // ExoPlayer: gapless looping for the multi-section engine sound composition
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    // Wear OS Data Layer (talks to the wear/ companion module on paired watches)
    implementation(libs.play.services.wearable)

    // CameraX: Overlay Studio camera viewports
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)

    // Unit tests for pure-Kotlin parsers (VariaAdapter, etc.)
    testImplementation(libs.junit)
}
