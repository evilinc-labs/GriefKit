pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.5.1"
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"
    shared {
        versions("1.21.4", "1.21.5", "1.21.8", "1.21.11")
    }
    create(rootProject)
}

rootProject.name = "GriefKit"
