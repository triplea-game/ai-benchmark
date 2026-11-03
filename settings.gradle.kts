@file:Suppress("UnstableApiUsage") // For repository declarations in settings

import org.gradle.api.initialization.resolve.RepositoriesMode

// Builds against triplea cloned next to this repo (../triplea), or the clone named by -Ptriplea=
pluginManagement {
    includeBuild((providers.gradleProperty("triplea").orNull ?: "../triplea") + "/gradle/build-logic")
}

val triplea = providers.gradleProperty("triplea").orNull ?: "../triplea"

plugins {
    id("org.triplea.failure-summary-plugin")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("$triplea/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "ai-benchmark"

includeBuild(triplea)

include(":runner")
project(":runner").projectDir = file("runner")
include(":analysis")
