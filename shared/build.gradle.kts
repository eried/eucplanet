// The code the phone app and the iOS app share. Android consumes it as a
// library (:app depends on it); iOS links it as the Shared framework.
// See docs/IOS_MIGRATION_PLAN.md and docs/IOS_SYNC.md.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    // Room's code generation for the shared AppDatabase.
    alias(libs.plugins.ksp)
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
            // Drag-to-reorder in the alarm list (multiplatform since 2.x).
            implementation(libs.reorderable)
            api(libs.cmp.resources)
            // The trip / alarm / wheel profile database. Android builds it as
            // before (framework SQLite, its migrations, in :app); iOS opens it
            // on the bundled SQLite driver.
            api(libs.room.runtime)
            // Settings live in one JSON blob in DataStore, on both phones.
            api(libs.datastore.preferences.core)
        }
        iosMain.dependencies {
            implementation(libs.sqlite.bundled)
        }
        androidMain.dependencies {
            // platform.HttpClient keeps the phone app's HTTP stack.
            implementation(libs.okhttp)
            // The Bluetooth-enable prompt before a scan (rememberScanStarter).
            implementation(libs.androidx.activity.compose)
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
            // QrEncoderZxingTest checks the shared QR encoder against the one Android draws with.
            implementation(libs.zxing.core)
        }
    }
}

// Room for every target. The schema of each version still goes to
// app/schemas, where MigrationAllTest reads it.
dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
ksp {
    arg("room.schemaLocation", "${rootDir}/app/schemas")
    arg("room.generateKotlin", "true")
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

// The iPhone's trip map inlines the same Leaflet the phone app ships in its
// assets, so it draws without the CDN. iOS resources only: the APK already
// carries these files.
val iosLeaflet = tasks.register<Sync>("generateIosLeaflet") {
    from(rootProject.file("app/src/main/assets")) { include("leaflet.js", "leaflet.css") }
    into(layout.buildDirectory.dir("generated/iosLeaflet/files"))
}
compose.resources {
    customDirectory(
        sourceSetName = "iosMain",
        directoryProvider = iosLeaflet.map { layout.buildDirectory.dir("generated/iosLeaflet").get() },
    )
}

// The welcome tour's clip and the launcher artwork for About, from the phone
// app's assets and drawables. iOS resources only: the APK already carries them.
val iosAppArt = tasks.register<Sync>("generateIosAppArt") {
    from(rootProject.file("app/src/main/assets")) {
        include("welcome_clip.webp")
        into("files")
    }
    from(rootProject.file("app/src/main/res/drawable")) {
        include("ic_launcher_foreground.xml")
        into("drawable")
    }
    into(layout.buildDirectory.dir("generated/iosAppArt"))
}
compose.resources {
    customDirectory(
        sourceSetName = "iosMain",
        directoryProvider = iosAppArt.map { layout.buildDirectory.dir("generated/iosAppArt").get() },
    )
}

// The settings search reads its words from the settings code, not a hand list.
apply(from = "settings-search-index.gradle.kts")
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(tasks.named("generateSettingsSearchIndex"))

// The map layer table is hud-protocol's MapLayers.kt, which the HUD app reads
// and which cannot depend on :shared. Shared code compiles the same file under
// its own package (com.eried.eucplanet.map), so the trip viewer's Leaflet page
// on both phones reads the very table the navigator, the Studio and the HUD do.
val sharedMapLayers = tasks.register<Sync>("generateSharedMapLayers") {
    from(rootProject.file("hud-protocol/src/main/java/com/eried/eucplanet/hud/protocol/MapLayers.kt"))
    into(layout.buildDirectory.dir("generated/mapLayers/com/eried/eucplanet/map"))
    filter { line ->
        line.replace("package com.eried.eucplanet.hud.protocol", "package com.eried.eucplanet.map")
    }
}
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(sharedMapLayers)

// The engine tests read the grain banks where the phone app ships them
// (app/src/main/res/raw), on the JVM and on the iOS simulator alike, so the
// banks stay one copy. The folder's absolute path is written into a test
// source here; nothing is committed.
val testGrainDir = tasks.register("generateTestGrainDir") {
    val dir = rootProject.file("app/src/main/res/raw").absolutePath.replace('\\', '/')
    val out = layout.buildDirectory.dir("generated/testGrainDir")
    inputs.property("dir", dir)
    outputs.dir(out)
    doLast {
        val file = out.get().asFile.resolve("com/eried/eucplanet/audio/TestGrainDir.kt")
        file.parentFile.mkdirs()
        file.writeText("package com.eried.eucplanet.audio\n\ninternal const val TEST_GRAIN_DIR = \"$dir\"\n")
    }
}
kotlin.sourceSets.getByName("commonTest").kotlin.srcDir(testGrainDir)

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
