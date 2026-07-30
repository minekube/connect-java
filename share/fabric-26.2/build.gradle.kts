plugins {
    id("net.fabricmc.fabric-loom")
    id("org.jetbrains.kotlin.jvm")
}

base {
    archivesName = "connect-share-fabric-26.2"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

kotlin {
    jvmToolchain(25)
}

repositories {
    maven("https://repo.opencollab.dev/maven-releases") {
        mavenContent { releasesOnly() }
    }
    maven("https://repo.opencollab.dev/maven-snapshots") {
        mavenContent { snapshotsOnly() }
    }
    maven("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/")
    maven("https://dl.cloudsmith.io/public/consensys/maven/maven/")
    maven("https://jitpack.io") {
        content { includeGroupByRegex("com\\.github\\..*") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:${Versions.fabricLoaderVersion}")
    implementation("net.fabricmc.fabric-api:fabric-api:${Versions.fabricApi262Version}")
    implementation("net.fabricmc:fabric-language-kotlin:${Versions.fabricLanguageKotlinVersion}")

    implementation(projects.core)
    implementation(projects.share.common)
    implementation(projects.share.fabricCommon)

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
