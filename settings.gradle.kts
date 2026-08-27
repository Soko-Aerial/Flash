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

rootProject.name = "Flash"
include(":app")
include(":core:common")
include(":core:persistence")
include(":core:security")
include(":core:discovery")
include(":core:network")
include(":core:transfer")
include(":core:messaging")
include(":core:engine")
include(":ui:theme")
include(":ui:chat")

// Test-harness consumers that reproduce a downstream compile classpath (Phase 2 Task 2.3).
// NOT published — they have no maven-publish plugin. See docs/publishing/PHASE-02-dependency-scope.md.
include(":sample:consumer")
include(":sample:consumer-granular")
