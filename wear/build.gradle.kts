import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "com.eried.eucplanet.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eried.eucplanet"
        minSdk = 30
        targetSdk = 35
        // Wear OS variant rides on the same package as the phone, so we offset
        // its versionCode by 100000 to keep them distinct in Play Console while
        // preserving the phone-side numbering (37 -> 100037).
        versionCode = 100243
        versionName = "0.9.17"
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
            // R8 shrinking cuts the wear APK from ~42 MB to ~8 MB by
            // tree-shaking Material Icons Extended and unused Compose paths.
            // proguard-rules.pro keeps the Data Layer bridge classes the
            // companion phone app sends messages to via reflection-y APIs.
            isMinifyEnabled = true
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

    // Output APKs as wearos-<buildtype>.apk to mirror app/'s phone-* prefix.
    // The wear-prefix lets adb / scripts / CI globs identify which artifact
    // came from which module without inspecting paths.
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "wearos-${buildType.name}.apk"
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.navigation)
    implementation(libs.compose.material.icons)

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)

    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.play.services.wearable)
}
