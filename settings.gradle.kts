@file:Suppress("UnstableApiUsage")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Geyser, Cumulus etc. TODO remove
        maven("https://repo.opencollab.dev/maven-releases") {
            mavenContent { releasesOnly() }
        }
        maven("https://repo.opencollab.dev/maven-snapshots") {
            mavenContent { snapshotsOnly() }
        }

        // Paper, Velocity
//        maven("https://papermc.io/repo/repository/maven-public")
        maven("https://repo.papermc.io/repository/maven-public")
        // Spigot
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots") {
            mavenContent { snapshotsOnly() }
        }

        // BungeeCord
        maven("https://oss.sonatype.org/content/repositories/snapshots") {
            mavenContent { snapshotsOnly() }
        }

        maven("https://libraries.minecraft.net") {
            name = "minecraft"
            mavenContent { releasesOnly() }
        }

        mavenCentral()

        maven("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/") {
            name = "jvm-libp2p"
        }
        maven("https://dl.cloudsmith.io/public/consensys/maven/maven/") {
            name = "consensys"
        }

        maven("https://repo.viaversion.com") {
            name = "viaversion-repo"
        }

        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }

        // Test-only. PaperMC publishes velocity-api to Maven but not the Velocity *proxy*, and
        // :velocity:eventOrderTest asserts Connect's login re-assert against the real
        // VelocityEventManager rather than a re-implementation of its ordering rules. The proxy
        // jar is served from a content-addressed CDN, so the sha256 in the URL pins the exact
        // artifact. Restricted to that single module; nothing else may resolve from here.
        ivy("https://fill-data.papermc.io/v1/objects/fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e/") {
            name = "papermc-fill-velocity-proxy"
            patternLayout { artifact("[module]-[revision].jar") }
            metadataSources { artifact() }
            content { includeModule("com.velocitypowered.proxy-jar", "velocity") }
        }

    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    repositories {
        maven("https://plugins.gradle.org/m2/")
    }
    plugins {
        id("net.kyori.blossom") version "1.2.0"
        id("net.kyori.indra")
        id("net.kyori.indra.git")
        id("com.google.protobuf") version "0.8.18"
    }
    includeBuild("build-logic")
}

rootProject.name = "connect-parent"

include(":api")
include(":core")
include(":bungee")
include(":spigot")
include(":velocity")
