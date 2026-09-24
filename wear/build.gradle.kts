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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.eried.eucplanet"
        minSdk = 30
        targetSdk = 36
        // The wear APK shares the phone's applicationId, so Play needs its
        // versionCode unique within the package and higher than the last wear
        // upload. It is its own 1002xx series and does not track the phone's
        // number: always increase it, never lower it to match, a lower code
        // is a downgrade Play rejects.
        versionCode = 100267
        versionName = "0.18.0"
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
    implementation(project(":hud-protocol"))

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
    testImplementation(libs.junit)
}
