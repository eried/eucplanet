plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Dev-only "fake Pebble app" (a stand-in for the Core Devices CoreApp). It
// implements the PebbleKitAndroid2 SERVER side: the EUC phone app discovers it
// as the installed Pebble app, hands it telemetry over Android IPC, and this
// module forwards that telemetry into the QEMU emulator. Lets the whole phone
// side be exercised with no Pebble hardware. Never shipped.
android {
    namespace = "com.eried.eucshim"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eried.eucshim"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // pebblekit2 1.1.0 ships Kotlin 2.3 metadata; this build's compiler is
        // 2.0.21. Same flag the :app pebbleEnabled path uses.
        freeCompilerArgs = freeCompilerArgs + "-Xskip-metadata-version-check"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(libs.pebblekit2.server)
    implementation(libs.coroutines.android)

    // Pin androidx.core down (pebblekit2 1.1.0 pulls 1.17.0, which would force
    // compileSdk 36 / AGP 8.9.1) — same as :app. But UNLIKE :app, do NOT pin
    // kotlin-stdlib down: this module actually invokes pebblekit2's server-side
    // suspend functions (DefaultPebbleListenerConnector), whose Kotlin-2.3.20
    // bytecode references kotlin.coroutines.jvm.internal.SpillingKt — a class
    // absent from the 2.0.21 stdlib. Let stdlib resolve up to pebblekit2's
    // 2.3.20 (a superset, so the 2.0.21-compiled shim code still runs) or the
    // listener connector crashes with ClassNotFoundException at runtime.
    constraints {
        implementation(libs.androidx.core) {
            version { strictly("1.15.0") }
            because("pebblekit2 pulls core 1.17.0 which needs compileSdk 36 / AGP 8.9.1")
        }
        implementation("androidx.core:core:1.15.0") {
            version { strictly("1.15.0") }
            because("pebblekit2 pulls core 1.17.0 which needs compileSdk 36 / AGP 8.9.1")
        }
    }
}
