import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.jar.JarFile

plugins {
    id("connect.base-conventions")
    id("com.gradleup.shadow")
}

tasks {
    named<Jar>("jar") {
        archiveClassifier.set("unshaded")
        from(project.rootProject.file("LICENSE"))
    }
    val shadowJar = named<ShadowJar>("shadowJar") {
        archiveBaseName.set("connect-${project.name}")
        archiveVersion.set("")
        archiveClassifier.set("")

        val sJar: ShadowJar = this

        doFirst {
            providedDependencies[project.name]?.forEach { (name, notation) ->
                sJar.dependencies {
                    println("Excluding $name from ${project.name}")
                    exclude(dependency(notation))
                }
            }

            // relocations made in included project dependencies are for whatever reason not
            // forwarded to the project implementing the dependency.
            // (e.g. a relocation in `core` will relocate for core. But when you include `core` in
            // for example Velocity, the relocation will be gone for Velocity)
            addRelocations(project, sJar)
        }
    }
    named("build") {
        dependsOn(shadowJar)
    }
    val verifyLibp2pRuntimeIsolation = register("verifyLibp2pRuntimeIsolation") {
        dependsOn(shadowJar)
        group = "verification"
        description = "Verifies libp2p runtime classes are isolated from parent-facing Connect classes."
        doLast {
            verifyLibp2pRuntimeIsolation(shadowJar.get().archiveFile.get().asFile)
        }
    }
    named("check") {
        dependsOn(verifyLibp2pRuntimeIsolation)
    }
}

fun verifyLibp2pRuntimeIsolation(jarFile: File) {
    JarFile(jarFile).use { jar ->
        if (jar.getJarEntry("com/minekube/connect/tunnel/p2p/Libp2pEndpoint.class") == null) {
            return
        }

        fun requireEntry(name: String): ByteArray {
            val entry = jar.getJarEntry(name)
                ?: error("${jarFile.name} is missing $name")
            return jar.getInputStream(entry).use { it.readBytes() }
        }

        fun ByteArray.containsConstant(value: String): Boolean =
            String(this, Charsets.ISO_8859_1).contains(value)

        val endpointWrapper = requireEntry("com/minekube/connect/tunnel/p2p/Libp2pEndpoint.class")
        val transportWrapper = requireEntry("com/minekube/connect/tunnel/p2p/Libp2pTunnelTransport.class")
        val endpointRuntime = requireEntry("com/minekube/connect/tunnel/p2p/Libp2pEndpointRuntime.class")
        val transportRuntime = requireEntry("com/minekube/connect/tunnel/p2p/impl/Libp2pTunnelTransportRuntime.class")
        val localSession = requireEntry("com/minekube/connect/network/netty/LocalSession.class")

        listOf(
            "io/libp2p",
            "io/netty",
            "Lio/libp2p",
            "Lio/netty",
        ).forEach { forbidden ->
            check(!endpointWrapper.containsConstant(forbidden)) {
                "Libp2pEndpoint parent wrapper must not reference $forbidden in ${jarFile.name}"
            }
            check(!transportWrapper.containsConstant(forbidden)) {
                "Libp2pTunnelTransport parent wrapper must not reference $forbidden in ${jarFile.name}"
            }
        }

        check(endpointRuntime.containsConstant("io/libp2p")) {
            "Libp2pEndpointRuntime should contain libp2p references in ${jarFile.name}"
        }
        check(transportRuntime.containsConstant("io/netty")) {
            "Libp2pTunnelTransportRuntime should contain runtime Netty references in ${jarFile.name}"
        }
        check(localSession.containsConstant("io/netty")) {
            "LocalSession should keep server Netty references in ${jarFile.name}"
        }
        check(!localSession.containsConstant("com/minekube/connect/libp2p/shadow/io/netty")) {
            "LocalSession must not be rewritten to private libp2p Netty in ${jarFile.name}"
        }
    }
}

fun addRelocations(project: Project, shadowJar: ShadowJar) {
    callAddRelocations(project.configurations.api.get(), shadowJar)
    callAddRelocations(project.configurations.implementation.get(), shadowJar)

    relocatedPackages[project.name]?.forEach { pattern ->
        println("Relocating $pattern for ${shadowJar.project.name}")
        shadowJar.relocate(pattern, "com.minekube.connect.shadow.$pattern")
    }
}

fun callAddRelocations(configuration: Configuration, shadowJar: ShadowJar) =
    configuration.dependencies.forEach {
        if (it is ProjectDependency)
            addRelocations(it.dependencyProject, shadowJar)
    }
