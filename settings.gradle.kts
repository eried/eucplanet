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
// Dev-only fake Pebble app: stands in for the CoreApp so the EUC phone app's
// telemetry can be tested against the QEMU emulator with no Pebble hardware.
// Not part of any shipped artifact. See pebble-emu-shim/README.md.
include(":pebble-emu-shim")
