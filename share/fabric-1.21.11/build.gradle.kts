plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("org.jetbrains.kotlin.jvm")
}

base {
    archivesName = "connect-share-fabric-1.21.11"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
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
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${Versions.fabricLoaderVersion}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${Versions.fabricApi12111Version}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${Versions.fabricLanguageKotlinVersion}")

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
