plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.eried.eucplanet.hud.protocol"
    compileSdk = 35

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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
}
