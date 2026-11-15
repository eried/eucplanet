pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "EucPlanet"
include(":app")
include(":wear")
include(":hud")
include(":hud-protocol")
include(":brouter")
// The upstream Java BRouter, test-only: the reference :brouter is checked against.
include(":brouter-reference")
project(":brouter-reference").projectDir = file("brouter/reference")
include(":shared")
