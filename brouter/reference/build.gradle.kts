// The upstream BRouter 1.7.10 Java, kept test-only as the reference the
// Kotlin copy in :brouter is checked against (RouteEquivalenceTest). The
// sources in upstream/ are the files as they were vendored; they compile under
// the package reference.btools so both copies load side by side.

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val renamed = tasks.register<Sync>("renameReferencePackages") {
    from("upstream")
    into(layout.buildDirectory.dir("generated/reference/reference"))
    filter { line -> line.replace(Regex("""\bbtools\."""), "reference.btools.") }
}

sourceSets {
    main {
        java.setSrcDirs(listOf(renamed.map { layout.buildDirectory.dir("generated/reference").get() }))
    }
}
