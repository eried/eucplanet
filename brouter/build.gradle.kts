// BRouter 1.7.10 (https://github.com/abrensch/brouter, MIT), vendored for
// offline routing. Plain Java, no Android code. See README.md for changes.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit)
}
