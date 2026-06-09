import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets only build on macOS. On Windows/Linux they are skipped so the
    // Android-green loop stays clean; iOS compilation is verified on the Mac.
    if (HostManager.hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
    }

    compilerOptions {
        // We intentionally use expect/actual objects (e.g. the Log facade);
        // opt into the Beta feature to silence the KT-61573 warning.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.eried.eucplanet.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
