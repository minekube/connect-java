import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("connect.shadow-conventions")
    id("net.fabricmc.fabric-loom-remap")
    id("org.jetbrains.kotlin.jvm")
}

base {
    archivesName = "connect-share-fabric-1.21.11"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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

val connectShareParentRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
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
    connectShareParentRuntime(projects.share.fabricCommon) {
        exclude(group = "io.libp2p")
        exclude(group = "io.netty")
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "com.google.errorprone", module = "javac")
    }

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.remapJar)
    systemProperty(
        "connectShareArtifact",
        tasks.remapJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
    )
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

relocate("arrow")
relocate("aopalliance")
relocate("cloud.commandframework")
relocate("com.google.common")
relocate("com.google.gson")
relocate("com.google.inject")
relocate("com.google.protobuf")
relocate("it.unimi.dsi.fastutil")
relocate("io.grpc")
relocate("io.leangen.geantyref")
relocate("jakarta.inject")
relocate("javax.inject")
relocate("okhttp3")
relocate("okio")
relocate("org.bstats")
relocate("org.geysermc.configutils")
relocate("org.yaml.snakeyaml")

val libp2pRuntimeJar = tasks.named<ShadowJar>("libp2pRuntimeJar") {
    dependsOn(":core:classes")
    from(rootProject.project(":core").layout.buildDirectory.dir("classes/java/main")) {
        include(
            "com/minekube/connect/tunnel/p2p/DirectP2pNodeRuntime*.class",
            "com/minekube/connect/tunnel/p2p/Libp2pEndpointRuntime*.class",
            "com/minekube/connect/tunnel/p2p/impl/Libp2pTunnelTransportRuntime*.class",
        )
    }
}
val minecraftGameProfileFactory =
    "com/minekube/connect/share/fabric/v1_21_11/" +
        "MinecraftGameProfileFactory.class"
val connectShareShadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(connectShareParentRuntime)
    archiveBaseName.set("connect-share-fabric-1.21.11")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("dev-parent-shadow")
    mergeServiceFiles()
    from(rootProject.file("LICENSE"))
    // Authlib's PropertyMap constructor must keep Minecraft's Guava ABI.
    exclude(minecraftGameProfileFactory)
}
val connectShareJar = tasks.register<Jar>("connectShareJar") {
    dependsOn(connectShareShadowJar, libp2pRuntimeJar)
    archiveBaseName.set("connect-share-fabric-1.21.11")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("dev-shadow")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        zipTree(connectShareShadowJar.get().archiveFile.get().asFile)
    })
    from(libp2pRuntimeJar) {
        into("META-INF/connect")
    }
    from(sourceSets.main.get().output) {
        include(minecraftGameProfileFactory)
    }
}

tasks.remapJar {
    dependsOn(connectShareJar)
    inputFile.set(connectShareJar.flatMap { it.archiveFile })
    archiveBaseName.set("connect-share-fabric-1.21.11")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

val verifyConnectShareArtifactSize = tasks.register("verifyConnectShareArtifactSize") {
    dependsOn(tasks.remapJar)
    val artifact = tasks.remapJar.flatMap { it.archiveFile }
    inputs.file(artifact)
    doLast {
        val bytes = artifact.get().asFile.length()
        val limit = 90L * 1024L * 1024L
        logger.lifecycle("Connect Share Fabric 1.21.11 artifact: {} MiB", "%.1f".format(bytes / 1024.0 / 1024.0))
        check(bytes <= limit) { "Connect Share Fabric 1.21.11 exceeds the 90 MiB release budget ($bytes bytes)" }
    }
}
tasks.check { dependsOn(verifyConnectShareArtifactSize) }
