import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("connect.shadow-conventions")
    id("net.neoforged.moddev")
    id("org.jetbrains.kotlin.jvm")
}

base {
    archivesName = "connect-share-neoforge-1.21.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    sourceSets.main {
        kotlin.srcDir("../fabric-1.21.1/src/main/kotlin")
        kotlin.exclude(
            "com/minekube/connect/share/fabric/v1_21_1/FabricConnectShare1211Client.kt",
            "com/minekube/connect/share/fabric/v1_21_1/FriendCardNetworking.kt",
        )
    }
}

neoForge {
    version = "21.1.247"
    validateAccessTransformers = true
    runs {
        create("client") { client() }
    }
    if (!providers.gradleProperty("connectShareArtifactSmoke").isPresent) {
        mods {
            create("connect_share") {
                sourceSet(sourceSets.main.get())
            }
        }
    }
}

sourceSets.main {
    java.srcDir("../fabric-1.21.1/src/main/java")
    resources.srcDir("../fabric-1.21.1/src/main/resources")
    resources.exclude("fabric.mod.json")
}

repositories {
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://repo.opencollab.dev/maven-releases")
    maven("https://repo.opencollab.dev/maven-snapshots")
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
    implementation("thedarkcolour:kotlinforforge:5.12.0")
    compileOnly("org.jspecify:jspecify:1.0.0")
    implementation(projects.core) {
        exclude(group = "io.netty")
    }
    implementation(projects.share.common) {
        exclude(group = "io.netty")
    }
    implementation(projects.share.fabricCommon) {
        exclude(group = "io.netty")
    }
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

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
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
relocate("com.google.thirdparty")
relocate("com.google")
relocate("it.unimi.dsi.fastutil")
relocate("io.grpc")
relocate("io.leangen.geantyref")
relocate("jakarta.inject")
relocate("javax.inject")
relocate("javax.annotation")
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
val connectShareShadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(connectShareParentRuntime)
    archiveBaseName.set("connect-share-neoforge-1.21.1")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("parent-shadow")
    mergeServiceFiles()
    from(rootProject.file("LICENSE"))
    exclude("org/checkerframework/**")
    exclude("org/jetbrains/annotations/**")
    exclude("org/jspecify/**")
    exclude("com/google/errorprone/**")
    exclude("com/google/j2objc/**")
    exclude("edu/umd/cs/findbugs/**")
    exclude("org/codehaus/mojo/animal_sniffer/**")
    exclude("module-info.class")
    exclude("META-INF/versions/*/module-info.class")
}
val connectShareJar = tasks.register<Jar>("connectShareJar") {
    dependsOn(connectShareShadowJar, libp2pRuntimeJar)
    archiveBaseName.set("connect-share-neoforge-1.21.1")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ zipTree(connectShareShadowJar.get().archiveFile.get().asFile) })
    from(libp2pRuntimeJar) {
        into("META-INF/connect")
    }
}
tasks.assemble { dependsOn(connectShareJar) }

tasks.test {
    useJUnitPlatform()
    dependsOn(connectShareJar)
    systemProperty(
        "connectShareArtifact",
        connectShareJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
    )
}

val verifyConnectShareArtifactSize = tasks.register("verifyConnectShareArtifactSize") {
    dependsOn(connectShareJar)
    val artifact = connectShareJar.flatMap { it.archiveFile }
    inputs.file(artifact)
    doLast {
        val bytes = artifact.get().asFile.length()
        val limit = 90L * 1024L * 1024L
        check(bytes <= limit) {
            "Connect Share NeoForge 1.21.1 exceeds the 90 MiB release budget ($bytes bytes)"
        }
    }
}
tasks.check { dependsOn(verifyConnectShareArtifactSize) }
