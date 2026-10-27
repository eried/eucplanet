// Kotlin 2.3 needs R8 8.13.19 or later; AGP 8.10 bundles 8.10, which cannot
// parse Kotlin 2.3 metadata in the release build. See
// developer.android.com/build/kotlin-support.
buildscript {
    dependencies {
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.play.publisher) apply false
}
