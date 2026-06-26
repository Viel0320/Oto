@file:Suppress("UnstableApiUsage")

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
// Stage 1 Foundation Modules (Extract pure settings, network policy, and lifecycle policy before heavier domains)
include(":settings:model")
include(":network:policy")
include(":runtime:lifecycle")
// Stage 1B Runtime Observability Module (Owns Android-backed logging without depending on app internals)
include(":runtime:observability")
// Stage 3 Data Store Module (Owns Room, DataStore, gateway contracts, and persistence services)
include(":data:store")
