import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.neoforged.moddevgradle.legacyforge.dsl.MixinExtension

plugins {
    id("connect.shadow-conventions")
    id("net.neoforged.moddev.legacyforge")
    id("org.jetbrains.kotlin.jvm")
}

base {
    archivesName = "connect-share-forge-1.20.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    sourceSets.main {
        kotlin.srcDir("../fabric-1.20.1/src/main/kotlin")
        kotlin.exclude(
            "com/minekube/connect/share/fabric/v1_20_1/FabricConnectShare1201Client.kt",
            "com/minekube/connect/share/fabric/v1_20_1/FriendCardNetworking.kt",
        )
    }
}

legacyForge {
    version = "1.20.1-47.4.22"
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
    java.srcDir("../fabric-1.20.1/src/main/java")
    resources.srcDir("../fabric-1.20.1/src/main/resources")
    resources.exclude(
        "fabric.mod.json",
        "connect-share-fabric-1.20.1.mixins.json",
    )
}

val forgeMixinConfig = "connect-share-forge-1.20.1.mixins.json"
val forgeMixinRefmapName = "connect-share-forge-1.20.1.refmap.json"
val forgeMixin = extensions.getByType<MixinExtension>()
val forgeMixinRefmap = forgeMixin.add(sourceSets.main.get(), forgeMixinRefmapName)
forgeMixin.config(forgeMixinConfig)

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
    implementation("thedarkcolour:kotlinforforge:4.12.0")
    annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
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

val minecraftGameProfileFactory =
    "com/minekube/connect/share/fabric/v1_20_1/" +
        "MinecraftGameProfileFactory.class"
val connectShareShadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(connectShareParentRuntime)
    archiveBaseName.set("connect-share-forge-1.20.1")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("dev-parent-shadow")
    mergeServiceFiles()
    from(rootProject.file("LICENSE"))
    exclude(minecraftGameProfileFactory)
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
    archiveBaseName.set("connect-share-forge-1.20.1")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("dev-shadow")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes(
        "MixinConfigs" to forgeMixinConfig,
    )
    from({ zipTree(connectShareShadowJar.get().archiveFile.get().asFile) })
    from(libp2pRuntimeJar) {
        into("META-INF/connect")
    }
    from(sourceSets.main.get().output) {
        include(minecraftGameProfileFactory)
    }
    from(forgeMixinRefmap)
}
val reobfConnectShareJar = obfuscation.reobfuscate(
    connectShareJar,
    sourceSets.main.get(),
) {
    archiveBaseName.set("connect-share-forge-1.20.1")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}
tasks.assemble { dependsOn(reobfConnectShareJar) }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    manifest.attributes(
        "MixinConfigs" to forgeMixinConfig,
    )
    from(rootProject.file("LICENSE"))
}

tasks.test {
    useJUnitPlatform()
    dependsOn(reobfConnectShareJar)
    systemProperty(
        "connectShareArtifact",
        reobfConnectShareJar.flatMap { it.archiveFile }.get().asFile.absolutePath,
    )
}

val verifyConnectShareArtifactSize = tasks.register("verifyConnectShareArtifactSize") {
    dependsOn(reobfConnectShareJar)
    val artifact = reobfConnectShareJar.flatMap { it.archiveFile }
    inputs.file(artifact)
    doLast {
        val bytes = artifact.get().asFile.length()
        val limit = 90L * 1024L * 1024L
        check(bytes <= limit) {
            "Connect Share Forge 1.20.1 exceeds the 90 MiB release budget ($bytes bytes)"
        }
    }
}
tasks.check { dependsOn(verifyConnectShareArtifactSize) }
