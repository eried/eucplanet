import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "com.eried.eucplanet.hud"
    compileSdk = 35

    defaultConfig {
        // The Motoeye E6 is an aftermarket motorcycle/EUC HUD running stock-ish
        // Android (AA-style). It's not a watch and never ships through Play, so
        // we keep a distinct applicationId rather than riding on the phone one.
        // That way a rider can have both apps installed side by side on a
        // tablet during development without one masking the other.
        applicationId = "com.eried.eucplanet.hud"
        // The Motoeye E6 ships with what aftermarket vendors call "Android",
        // but the firmware revision is unpublished and the manuals are vague.
        // The EUC World HUD app (known to install) targets minSdk=24
        // (Android 7), so we match that. Some E6 units appear to be Android
        // 7 or 8 from forum reports, which would parse-reject any APK with
        // minSdk >= 26. targetSdk 33 keeps us inside the API surface we
        // actually exercise (Compose, CameraX, OkHttp all support 24).
        minSdk = 24
        targetSdk = 33
        // Offset by 300000 so the HUD APK's version line never collides with
        // the phone (1..99999) or the wear companion (100000-prefixed) when
        // both are visible in the same release notes.
        versionCode = 300005
        versionName = "0.1.4"
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
            isMinifyEnabled = false
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

dependencies {
    implementation(project(":hud-protocol"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.kotlinx.serialization.json)

    // Ktor server: the HUD listens for the phone's outbound WebSocket. Hosting
    // here (instead of on the phone) flips the dial direction so client
    // isolation / multicast filters on the phone's softAP don't block us --
    // outbound TCP almost always works even when peer-to-peer doesn't.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    // OkHttp powers the direct-to-CartoCDN map tile fetcher on the HUD side.
    // The HUD usually has its own internet through the rider's hotspot, so we
    // can skip the phone-as-proxy hop the previous build used.
    implementation(libs.okhttp)

    // JmDNS: the HUD advertises _eucplanet._tcp on whatever subnet it has,
    // so the phone can auto-discover when manual IP isn't set. When
    // multicast is filtered, the rider falls back to typing the IP shown on
    // the HUD's banner.
    implementation(libs.jmdns)

    // CameraX for the rear-camera screen. The Motoeye exposes the rear cam
    // through the standard camera2 HAL.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
}
