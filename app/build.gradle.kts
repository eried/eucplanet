import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.play.publisher)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "com.eried.eucplanet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eried.eucplanet"
        minSdk = 29
        targetSdk = 35
        versionCode = 240
        versionName = "0.9.14"

        val buildStamp = SimpleDateFormat("yyMMdd.HHmm")
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        buildConfigField("String", "BUILD_STAMP", "\"$buildStamp\"")

        // Current git branch, baked in at build time so the About dialog can
        // show which branch a build came from. Empty when git isn't available;
        // the UI hides the tag for "main" / detached HEAD.
        val gitBranch = try {
            val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").start()
            val out = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            if (process.exitValue() == 0) out else ""
        } catch (e: Exception) { "" }
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")

        buildConfigField("String", "EUCSTATS_API_BASE_URL", "\"https://eucstats.ried.no/api/v1\"")
        buildConfigField("long", "EUCSTATS_GCP_PROJECT_NUMBER", "0L")
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
        debug {
            // Debug builds target the PRODUCTION eucstats API by default, so
            // branch/sideload debug APKs work on real phones (10.0.2.2 is the
            // emulator-to-host alias and is unreachable on physical devices).
            // Opt into a local dev server with: ./gradlew assembleDebug -PeucstatsLocal
            if (project.hasProperty("eucstatsLocal")) {
                buildConfigField("String", "EUCSTATS_API_BASE_URL", "\"http://10.0.2.2:8000/api/v1\"")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // Fall back to the debug keystore so local release
                // builds work without a real release signing config and
                // sideload-update over an existing debug install without
                // forcing testers to uninstall first (mismatched
                // signatures otherwise). CI workflows that ship to Play
                // or GitHub Releases inject RELEASE_KEYSTORE secrets at
                // build time and pick the branch above instead, so
                // production releases never see this fallback.
                signingConfig = signingConfigs.getByName("debug")
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

    // Output APKs as phone-<buildtype>.apk (phone-debug.apk / phone-release.apk)
    // instead of the default app-<buildtype>.apk. Matches the wear module's
    // wearos-<buildtype>.apk naming so adb commands / CI artifact globs read
    // the same device prefix regardless of which side they came from. The
    // CI workflow (.github/workflows/branch-apk.yml) layers the branch +
    // short SHA on top so the pre-release filenames stay self-describing.
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "phone-${buildType.name}.apk"
        }
    }
}

// Wear companion embedding: scoped to the release variant of the Play-Store
// build and toggleable via -PembedWear=false. The wear module is a non-standalone
// companion (com.google.android.wearable.standalone="false" in wear/AndroidManifest.xml),
// so it has to ride inside the phone AAB for Google Play to auto-deliver it to a
// paired Wear OS device. Debug builds and the GitHub-sideload APK don't need that
// auto-delivery path, so we keep them slim (60 MB -> 20 MB phone APK).
//
// Note: `wearApp` is a legacy AGP DSL (Wear OS 1.x era). On our AGP (8.7.3) it
// works silently with no deprecation warning. AGP 8.x through 8.13 keeps it.
// **AGP 9.0.1 (January 2026) removes the DSL entirely** ("AGP 9.0 removes support
// for embedding Wear OS apps, which is no longer supported in Play"), so any
// upgrade to 9.x will break this build. See:
//   https://developer.android.com/build/releases/agp-9-0-0-release-notes
// Migration path when forced off 8.x: switch to Play Multi-APK delivery, drop
// this line, ship `wear-release.aab` as a separate AAB on the same applicationId
// to the Wear OS form-factor track in Play Console. Same end-user behaviour
// (Play auto-installs the watch app on paired devices when the phone app
// installs), just packaged as two AABs in one release instead of one embedded.
val embedWear = (project.findProperty("embedWear") as? String)?.lowercase() != "false"

// Garmin Connect IQ Mobile SDK (talks to a paired Garmin watch / Edge running
// the garmin-watch-app/ Monkey C companion). Pulled from Maven Central
// (`com.garmin.connectiq:ciq-companion-app-sdk`) so a fresh clone builds
// without any manual download.
//
// `garminStub` source set is the fallback when the build property
// `-PgarminEnabled=false` is set, useful for slim builds that don't need
// Garmin support (saves ~150 KB on the apk). Default is enabled.
val garminEnabled = (project.findProperty("garminEnabled") as? String)?.lowercase() != "false"

android {
    sourceSets {
        getByName("main") {
            kotlin.srcDir(if (garminEnabled) "src/garminEnabled/kotlin" else "src/garminStub/kotlin")
        }
    }
}

dependencies {
    if (embedWear) {
        "releaseWearApp"(project(":wear"))
    }
    if (garminEnabled) {
        implementation(libs.garmin.ciq)
    }

    // Shared wire-format types for the HUD companion app. Lives in its own
    // module so the phone and the HUD compile against the same Kotlin
    // classes, no manual JSON parity drift.
    implementation(project(":hud-protocol"))
    implementation(libs.kotlinx.serialization.json)

    // OkHttp powers the outbound WebSocket [HudServer] uses to dial the HUD
    // and push telemetry frames. Built-in WebSocket support, no extra module
    // needed beyond the core artifact.
    implementation(libs.okhttp)

    // JmDNS: when the rider leaves the HUD IP blank, the phone falls back
    // to mDNS auto-discovery, looking for the HUD's _eucplanet._tcp
    // advertisement on whatever subnet the phone has. Same library on
    // both ends so behaviour is consistent.
    implementation(libs.jmdns)

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)

    // Lifecycle
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore (settings live here as a single JSON blob so schema bumps
    // never wipe rider configuration. Room is reserved for trips, alarms
    // and per-wheel profiles, which get real migrations.)
    implementation(libs.datastore.preferences)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Location
    implementation(libs.play.services.location)

    // MapLibre - TODO: re-enable when map preview is implemented
    // Disabled for now to avoid 16KB page size compatibility warning on Android 15+
    // implementation(libs.maplibre)

    // Flic 2 button SDK
    implementation(libs.flic2)

    // WorkManager (background trip upload)
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Drag-to-reorder for settings lists
    implementation(libs.reorderable)

    // ExoPlayer: gapless looping for the multi-section engine sound composition
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    // Wear OS Data Layer (talks to the wear/ companion module on paired watches)
    implementation(libs.play.services.wearable)

    // ZXing core: pure-Java QR code generator used by the dashboard
    // custom-tile "Show QR" action. No Android-camera deps included; we
    // only need the encoder side.
    implementation(libs.zxing.core)

    // CameraX: Overlay Studio camera viewports
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)

    // Play Integrity API (Standard Integrity Manager for request-hash-bound tokens)
    implementation("com.google.android.play:integrity:1.4.0")

    // Unit tests for pure-Kotlin parsers (VariaAdapter, etc.)
    testImplementation(libs.junit)
    // org.json is bundled in the Android SDK but not available on the JVM test
    // classpath. Adding the standalone artifact makes SettingsJson JVM tests work
    // without Robolectric.
    testImplementation("org.json:json:20240303")
    // MockWebServer for EucStatsApi JVM tests — version must match libs.okhttp (4.12.0)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// Gradle Play Publisher -- LOCAL publishing only (no browser, NOT wired into CI):
//   ./gradlew :app:publishReleaseBundle                     -> Open testing (beta)
//   ./gradlew :app:publishReleaseBundle --track production  -> Production
// Default track is beta; --track overrides per run (also --release-status draft
// or --user-fraction 0.1 for a held / staged push). releaseStatus = COMPLETED
// means the upload is sent for review and auto-publishes on approval.
// Credentials: play-service-account.json at the repo root (gitignored).
// Release notes come from src/main/play/release-notes/en-US/default.txt, which
// is rewritten from reviewed text at release time (drafted + approved, never
// auto-generated from commit messages).
play {
    val playCreds = rootProject.file("play-service-account.json")
    if (playCreds.exists()) {
        serviceAccountCredentials.set(playCreds)
    }
    track.set("beta") // default; override per run with --track production
    defaultToAppBundles.set(true)
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.COMPLETED)
}
