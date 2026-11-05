// The code the phone app and the iOS app share. Android consumes it as a
// library (:app depends on it); iOS links it as the Shared framework.
// See docs/IOS_MIGRATION_PLAN.md and docs/IOS_SYNC.md.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
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
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.material3)
            // ActionCatalog and the dashboard draw the same Material icons as :app.
            implementation(libs.cmp.material.icons)
            // DashboardViewModel extends the multiplatform ViewModel.
            api(libs.cmp.lifecycle.viewmodel)
            // collectAsStateWithLifecycle and LocalLifecycleOwner in the dashboard.
            implementation(libs.cmp.lifecycle.runtime.compose)
            api(libs.cmp.resources)
            // @Entity and friends on the moved models; the database stays in :app for now.
            implementation(libs.room.common)
            // Settings live in one JSON blob in DataStore, on both phones.
            api(libs.datastore.preferences.core)
        }
        androidMain.dependencies {
            // platform.HttpClient keeps the phone app's HTTP stack.
            implementation(libs.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The JVM tests moved from :app keep their JUnit 4 form.
        getByName("androidUnitTest").dependencies {
            implementation(libs.junit)
            // AppSettingsArgLimitTest walks the constructor by reflection.
            implementation(kotlin("reflect"))
            // The platform JSON facade is the framework's org.json, which the
            // stub android.jar cannot run.
            implementation("org.json:json:20240303")
        }
    }
}

// The 23 locales stay in app/src/main/res; iOS gets them through this.
apply(from = "compose-strings.gradle.kts")

compose.resources {
    publicResClass = true
    packageOfResClass = "com.eried.eucplanet.resources"
    generateResClass = always
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = tasks.named("generateComposeStrings").map {
            layout.buildDirectory.dir("generated/composeStrings").get()
        },
    )
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
