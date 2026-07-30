package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.ShareCoordinator
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint
import com.minekube.connect.tunnel.p2p.Libp2pTunnelTransport
import java.nio.file.Files
import java.nio.file.Path
import java.net.URLClassLoader
import java.util.jar.JarInputStream
import java.util.jar.JarFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Fabric12111ArtifactTest {
    @Test
    fun `artifact uses a friends first sharing vocabulary`() {
        JarFile(artifact().toFile()).use { jar ->
            val language = jar.getInputStream(
                jar.getJarEntry(
                    "assets/connect-share/lang/en_us.json",
                ),
            ).bufferedReader().use { it.readText() }

            assertTrue(
                "\"connect_share.setup.title\": \"Share this world\"" in
                    language,
            )
            assertTrue(
                "\"connect_share.status.copy_invitation\": " +
                    "\"Copy friend link\"" in language,
            )
        }
    }

    @Test
    fun `remapped artifact is self contained and isolates networking runtime`() {
        JarFile(artifact().toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toSet()

            assertTrue("fabric.mod.json" in entries)
            assertTrue("LICENSE" in entries)
            assertTrue("connect-share-fabric-1.21.11.mixins.json" in entries)
            assertTrue(
                "com/minekube/connect/share/fabric/v1_21_11/" +
                    "FriendCardNetworking.class" in entries,
            )
            assertTrue(
                entries.any {
                    it.startsWith("com/minekube/connect/share/") &&
                        it.endsWith(".class")
                },
            )
            assertTrue("META-INF/connect/libp2p-runtime.jar" in entries)
            assertFalse(entries.any { it.startsWith("io/libp2p/") })
            assertFalse(entries.any { it.startsWith("io/netty/") })
            assertFalse(entries.any { it.startsWith("it/unimi/dsi/fastutil/") })
            assertFalse(entries.any { it.startsWith("kotlin/") })
            assertTrue(
                entries.any {
                    it.startsWith(
                        "com/minekube/connect/shadow/it/unimi/dsi/fastutil/",
                    )
                },
            )

            val payload = jar.getJarEntry("META-INF/connect/libp2p-runtime.jar")
            val payloadEntries = JarInputStream(jar.getInputStream(payload)).use { nested ->
                generateSequence(nested::getNextJarEntry).map { it.name }.toSet()
            }
            assertTrue(payloadEntries.any { it.startsWith("io/libp2p/") })
            assertTrue(payloadEntries.any { it.startsWith("io/netty/") })
            assertTrue(payloadEntries.any { it.startsWith("kotlin/") })
            assertTrue(
                "com/minekube/connect/tunnel/p2p/DirectP2pNodeRuntime.class" in
                    payloadEntries,
            )
        }
    }

    @Test
    fun `minecraft profile mapper preserves Mojang Guava ABI`() {
        JarFile(artifact().toFile()).use { jar ->
            val factory = jar.getJarEntry(
                "com/minekube/connect/share/fabric/v1_21_11/" +
                    "MinecraftGameProfileFactory.class",
            )
            assertNotNull(factory)

            val bytecode = jar.getInputStream(factory).use {
                it.readBytes().toString(Charsets.ISO_8859_1)
            }
            assertTrue("com/google/common/collect/Multimap" in bytecode)
            assertTrue(
                "(Lcom/google/common/collect/Multimap;)V" in bytecode,
            )
            assertFalse(
                "com/minekube/connect/shadow/com/google/common" in bytecode,
            )
        }
    }

    @Test
    fun `packaged loader reads libp2p only from child payload`() {
        URLClassLoader(
            arrayOf(artifact().toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { artifactLoader ->
            val loaderType = Class.forName(
                "com.minekube.connect.tunnel.p2p.Libp2pRuntimeLoader",
                true,
                artifactLoader,
            )
            val loaderMethod = loaderType.getDeclaredMethod("classLoader")
                .apply { isAccessible = true }
            val runtimeLoader = loaderMethod.invoke(null) as ClassLoader
            try {
                val host = Class.forName(
                    "io.libp2p.core.Host",
                    false,
                    runtimeLoader,
                )
                val directRuntime = Class.forName(
                    "com.minekube.connect.tunnel.p2p.DirectP2pNodeRuntime",
                    false,
                    runtimeLoader,
                )
                assertTrue(host.classLoader === runtimeLoader)
                assertTrue(directRuntime.classLoader === runtimeLoader)
                val directCodeSource =
                    directRuntime.protectionDomain.codeSource.location
                assertNotNull(directCodeSource)
                assertTrue(
                    directCodeSource.toString().contains("libp2p-runtime-"),
                )
                val nodeType = Class.forName(
                    "com.minekube.connect.tunnel.p2p.DirectP2pNode",
                    true,
                    artifactLoader,
                )
                val node = nodeType.getDeclaredConstructor().newInstance()
                nodeType.getMethod("close").invoke(node)
            } finally {
                loaderType.getDeclaredMethod("close")
                    .apply { isAccessible = true }
                    .invoke(null)
            }
        }
    }

    @Test
    fun `parent facing APIs do not expose isolated runtime types`() {
        listOf(
            ConnectShareClient::class.java,
            ShareCoordinator::class.java,
            DirectP2pNode::class.java,
            Libp2pEndpoint::class.java,
            Libp2pTunnelTransport::class.java,
        ).forEach(::assertParentFacingTypes)
    }

    private fun assertParentFacingTypes(type: Class<*>) {
        val exposed = buildList {
            type.declaredFields.forEach { add(it.type.name) }
            type.declaredConstructors.forEach { constructor ->
                constructor.parameterTypes.forEach { add(it.name) }
            }
            type.declaredMethods.forEach { method ->
                add(method.returnType.name)
                method.parameterTypes.forEach { add(it.name) }
            }
        }
        val invalid = exposed.filter { name ->
            FORBIDDEN_TYPE_PREFIXES.any(name::startsWith)
        }
        assertTrue(
            invalid.isEmpty(),
            "${type.name} exposes isolated runtime types: $invalid",
        )
    }

    private fun artifact(): Path {
        val explicit = System.getProperty("connectShareArtifact")
        if (explicit != null) {
            return Path.of(explicit)
        }
        val directory = Path.of("build", "libs")
        return Files.list(directory).use { paths ->
            paths.filter {
                it.name.startsWith("connect-share-fabric-1.21.11-") &&
                    it.name.endsWith(".jar") &&
                    !it.name.contains("sources") &&
                    !it.name.contains("dev")
            }.findFirst().orElse(null)
        }.also(::assertNotNull)
    }

    private companion object {
        val FORBIDDEN_TYPE_PREFIXES = listOf(
            "io.libp2p.",
            "io.netty.",
        )
    }
}
