import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
        listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "Shared"
                isStatic = false
            }
        }
    }

    compilerOptions {
        // We intentionally use expect/actual objects (e.g. the Log facade);
        // opt into the Beta feature to silence the KT-61573 warning.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // iOS engine only configured on macOS (iOS targets are Mac-gated above).
        if (HostManager.hostIsMac) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
    }
}

// Reuse Android's strings.xml + translations via Compose Multiplatform Resources
// (files copied verbatim into commonMain/composeResources/values*). One source of
// truth for text on both platforms; the device locale picks the translation.
compose.resources {
    publicResClass = true
    generateResClass = always
    packageOfResClass = "com.eried.eucplanet.resources"
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
