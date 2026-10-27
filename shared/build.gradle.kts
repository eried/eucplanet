// The code the phone app and the iOS app share. Android consumes it as a
// library (:app depends on it); iOS links it as the Shared framework.
// See docs/IOS_MIGRATION_PLAN.md and docs/IOS_SYNC.md.

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

    // iOS only compiles on a Mac. Elsewhere (Windows, the Linux CI runners)
    // the targets are skipped (kotlin.native.ignoreDisabledTargets in
    // gradle.properties), and CommonMainGuardTest keeps Android-only APIs out
    // of commonMain there.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    compilerOptions {
        // expect/actual classes are Beta; the facades in platform/ use them.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // BleProfile carries kotlin.uuid.Uuid; :app converts at the GATT calls.
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.atomicfu)
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The JVM tests moved from :app keep their JUnit 4 form.
        getByName("androidUnitTest").dependencies {
            implementation(libs.junit)
            // AppSettingsArgLimitTest walks the constructor by reflection.
            implementation(kotlin("reflect"))
        }
    }
}

android {
    namespace = "com.eried.eucplanet.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Same as :app: logic under test crosses android.util.Log through
            // the Log facade, and the stub android.jar throws by default.
            isReturnDefaultValues = true
        }
    }
}
