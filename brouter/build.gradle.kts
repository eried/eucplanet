// BRouter 1.7.10 (https://github.com/abrensch/brouter, MIT), the offline
// router, in Kotlin Multiplatform so the phone app and the iPhone run one
// copy. Only the routing EUC Planet reaches is here; see README.md for what
// changed from upstream. The upstream Java stays test-only in reference/,
// where RouteEquivalenceTest routes the same pairs through both.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // iOS only compiles on a Mac; elsewhere the targets are skipped
    // (kotlin.native.ignoreDisabledTargets in gradle.properties).
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // IOException is an expect class (java.io's on the JVM).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.atomicfu)
        }
        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/testPaths"))
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmTest.dependencies {
            implementation(project(":brouter-reference"))
            implementation(libs.junit)
        }
    }
}

// The routing tests read the app's profiles where the APK ships them and the
// segments from BROUTER_SEGMENTS (a folder with .rd5 files, see README.md),
// on the JVM and on the iOS simulator alike. The paths are written into a
// test source here; nothing is committed.
val testPaths = tasks.register("generateTestPaths") {
    val profiles = rootProject.file("app/src/main/assets/brouter").absolutePath.replace('\\', '/')
    val segments = (providers.environmentVariable("BROUTER_SEGMENTS").orNull
        ?: providers.gradleProperty("brouterSegments").orNull
        ?: rootProject.file("brouter/testdata/segments").absolutePath).replace('\\', '/')
    val golden = layout.buildDirectory.file("brouter-golden.txt").get().asFile.absolutePath.replace('\\', '/')
    val out = layout.buildDirectory.dir("generated/testPaths")
    inputs.property("profiles", profiles)
    inputs.property("segments", segments)
    inputs.property("golden", golden)
    outputs.dir(out)
    doLast {
        val file = out.get().asFile.resolve("btools/router/TestPaths.kt")
        file.parentFile.mkdirs()
        file.writeText(
            "package btools.router\n\n" +
                "internal object TestPaths {\n" +
                "    const val PROFILES = \"$profiles\"\n" +
                "    const val SEGMENTS = \"$segments\"\n" +
                "    const val GOLDEN = \"$golden\"\n" +
                "}\n",
        )
    }
}
tasks.matching { it.name.startsWith("compileTestKotlin") || it.name.startsWith("compileKotlinJvmTest") || it.name == "compileTestKotlinJvm" }
    .configureEach { dependsOn(testPaths) }
// The iOS run reads the answers the JVM run takes from the Java reference.
tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach { mustRunAfter("jvmTest") }
// `gradlew test` keeps running this module's JVM tests, as it did when it was Java.
tasks.register("test") { dependsOn("jvmTest") }
