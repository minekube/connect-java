import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("connect.shadow-conventions")
    id("net.fabricmc.fabric-loom")
    id("org.jetbrains.kotlin.jvm")
}

base {
    archivesName = "connect-share-fabric-26.2"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
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

val connectShareParentRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:${Versions.fabricLoaderVersion}")
    implementation("net.fabricmc.fabric-api:fabric-api:${Versions.fabricApi262Version}")
    implementation("net.fabricmc:fabric-language-kotlin:${Versions.fabricLanguageKotlinVersion}")

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
    "com/minekube/connect/share/fabric/v26_2/" +
        "MinecraftGameProfileFactory.class"
val connectShareShadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(connectShareParentRuntime)
    archiveBaseName.set("connect-share-fabric-26.2")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("parent-shadow")
    mergeServiceFiles()
    from(rootProject.file("LICENSE"))
    // Authlib's PropertyMap constructor must keep Minecraft's Guava ABI.
    exclude(minecraftGameProfileFactory)
}
val connectShareJar = tasks.register<Jar>("connectShareJar") {
    dependsOn(connectShareShadowJar, libp2pRuntimeJar)
    archiveBaseName.set("connect-share-fabric-26.2")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
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

tasks.assemble {
    dependsOn(connectShareJar)
}

tasks.test {
    dependsOn(connectShareJar)
    systemProperty(
        "connectShareArtifact",
        connectShareJar.flatMap { it.archiveFile }
            .get()
            .asFile
            .absolutePath,
    )
}

val verifyConnectShareArtifactSize = tasks.register("verifyConnectShareArtifactSize") {
    dependsOn(connectShareJar)
    val artifact = connectShareJar.flatMap { it.archiveFile }
    inputs.file(artifact)
    doLast {
        val bytes = artifact.get().asFile.length()
        val limit = 90L * 1024L * 1024L
        logger.lifecycle("Connect Share Fabric 26.2 artifact: {} MiB", "%.1f".format(bytes / 1024.0 / 1024.0))
        check(bytes <= limit) { "Connect Share Fabric 26.2 exceeds the 90 MiB release budget ($bytes bytes)" }
    }
}
tasks.check { dependsOn(verifyConnectShareArtifactSize) }
