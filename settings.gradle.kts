@file:Suppress("UnstableApiUsage")

pluginManagement {
    // Convention Plugins (Shared Android library, Kotlin JVM target, and test wiring live in build-logic)
    includeBuild("build-logic")
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "oto"
include(":app")
// Stage 1 Foundation Modules (Keep lifecycle policy extracted before heavier domains)
include(":runtime:lifecycle")
// Stage 1B Runtime Observability Module (Owns Android-backed logging without depending on app internals)
include(":runtime:observability")
// Stage 3 Data Store Module (Owns Room, DataStore, gateway contracts, and persistence services)
include(":data:store")
// Stage 4B Library VFS Module (Owns source providers, VFS file access, and remote range cache behavior)
include(":library:vfs")
// Stage 5A Media Metadata Module (Owns audio metadata parsing, manifest parsing, cover extraction, and subtitle parsing)
include(":media:metadata")
// Stage 5B Media Playback Module (Owns playback plans, controller runtime, VFS playback data source, and recovery policies)
include(":media:playback")
// Stage 5C Media Service Module (Owns Media3 services, foreground notifications, and audio-focus runtime)
include(":media:service")
// Stage 6 ABS Module (Owns AudiobookShelf anti-corruption, sync, progress, and source adapter)
include(":abs")
// Stage 4C Work Policy Module (Owns reusable WorkManager queue policy shared by library import and ABS sync)
include(":work:policy")
// Stage 4D Library Import Module (Owns scan/import/root lifecycle while depending on VFS and metadata modules)
include(":library:import")
// Stage 7A Application Module (Owns read models, commands, use cases, and download orchestration)
include(":application")

// Stage 7C Event Module (Owns feedback delivery contracts without Android resources)
include(":event")

// Stage 7D Widget Module (Owns Glance widget rendering, state, and receivers)
include(":widget")

// Stage 7E Shared Module (Owns shared models, pure policies, and the consolidated user-visible resource catalog)
include(":shared")

// Stage 7G UI Module (Owns Compose routes, screens, overlays, ViewModels, theme, and locale UI support)
include(":ui")
