// The wire types the phone, the HUD app and the watch share. Multiplatform so
// the shared module (and with it the iPhone) can read the overlay layout, the
// HUD state and Web Mercator; the rest (watch map protocol, link watchdog,
// map layers, debug properties) stays Android-only in androidMain,
// as :hud and :wear use it. The phone link's pure rules (stale link, Wi-Fi
// interference, held heading, route window, app push) are common, for the iPhone.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // iOS only compiles on a Mac; elsewhere the targets are skipped
    // (kotlin.native.ignoreDisabledTargets in gradle.properties).
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // OverlayElement ids come from kotlin.uuid.Uuid.
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.coroutines.core)
            // Locks that hold on the iPhone too (@Synchronized does nothing there).
            implementation(libs.atomicfu)
        }
        getByName("androidUnitTest").dependencies {
            implementation(libs.junit)
        }
    }
}

android {
    namespace = "com.eried.eucplanet.hud.protocol"
    compileSdk = 36

    defaultConfig {
        // Pure data classes - no API surface that requires anything newer.
        // Keep low so :hud (which targets Android 7 to cover aftermarket
        // HUDs) can depend on us without lifting its own minSdk.
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
