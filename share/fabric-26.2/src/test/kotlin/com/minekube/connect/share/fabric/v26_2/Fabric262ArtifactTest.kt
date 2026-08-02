package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.ShareCoordinator
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint
import com.minekube.connect.tunnel.p2p.Libp2pTunnelTransport
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.jar.JarInputStream
import java.util.jar.JarFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Fabric262ArtifactTest {
    @Test
    fun `artifact uses a friends first sharing vocabulary`() {
        JarFile(artifact().toFile()).use { jar ->
            val language = jar.getInputStream(
                jar.getJarEntry(
                    "assets/connect-share/lang/en_us.json",
                ),
            ).bufferedReader().use { it.readText() }

            assertTrue(
                "\"connect_share.setup.title\": \"Play this world with friends\"" in
                    language,
            )
            assertTrue(
                "\"connect_share.status.copy_friend_link\": " +
                    "\"Copy invite for a new friend\"" in language,
            )
            assertTrue(
                "\"connect_share.friends.copy_my_link\": " +
                    "\"Copy my friend link\"" in language,
            )
            assertTrue(
                "\"connect_share.friends.send_request\": " +
                    "\"Send request\"" in language,
            )
            assertTrue(
                "\"connect_share.friends.outgoing_request\": " +
                    "\"Waiting for %s\"" in language,
            )
            assertTrue(
                "\"connect_share.friends.incoming_request\": " +
                    "\"%s wants to be friends · %s\"" in language,
            )
            assertTrue(
                "\"connect_share.friends.retry_request\": \"Retry\"" in
                    language,
            )
            assertTrue(
                "\"connect_share.friends.cancel_request\": \"Cancel\"" in
                    language,
            )
            assertTrue(
                "\"connect_share.status.allow\": \"Accept\"" in language,
            )
            assertTrue(
                "\"connect_share.status.deny\": \"Decline\"" in language,
            )
            assertFalse("connect_share.friends.accept_request" in language)
        }
    }

    @Test
    fun `friend removal confirmation stays inside the friends screen`() {
        JarFile(artifact().toFile()).use { jar ->
            val bytecode = jar.entries().asSequence()
                .filter {
                    it.name.startsWith(
                        "com/minekube/connect/share/fabric/v26_2/" +
                            "ShareJoinScreen",
                    ) && it.name.endsWith(".class")
                }
                .joinToString {
                    jar.getInputStream(it).use { stream ->
                        stream.readBytes().toString(Charsets.ISO_8859_1)
                    }
                }

            assertFalse(
                "net/minecraft/client/gui/screens/ConfirmScreen" in bytecode,
            )
            assertTrue(
                "connect_share.friends.remove_confirm.confirm" in bytecode,
            )
            assertTrue("sendRequest" in bytecode)
            assertTrue("suggestedDisplayName" in bytecode)
            assertTrue("FriendRequestClient" in bytecode)
            assertTrue("getIncomingRequests" in bytecode)
            assertTrue("connect_share.status.allow" in bytecode)
            assertTrue("connect_share.status.deny" in bytecode)
            assertTrue("joinOutgoing" !in bytecode)
            assertFalse("connect_share.friends.accept_request" in bytecode)
        }
    }

    @Test
    fun `approved card exchange promotes an outgoing request`() {
        JarFile(artifact().toFile()).use { jar ->
            val bytecode = jar.entries().asSequence()
                .filter {
                    it.name.startsWith(
                        "com/minekube/connect/share/fabric/v26_2/" +
                            "FriendCardNetworking",
                    ) && it.name.endsWith(".class")
                }
                .joinToString {
                    jar.getInputStream(it).use { input ->
                        input.readBytes().toString(Charsets.ISO_8859_1)
                    }
                }

            assertTrue("confirmOutgoing" in bytecode)
        }
    }

    @Test
    fun `artifact is self contained and isolates networking runtime`() {
        JarFile(artifact().toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toSet()

            assertTrue("fabric.mod.json" in entries)
            assertTrue("LICENSE" in entries)
            assertTrue("connect-share-fabric-26.2.mixins.json" in entries)
            assertTrue(
                "com/minekube/connect/share/fabric/v26_2/" +
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
            assertFalse(
                payloadEntries.any {
                    it.startsWith("org/bouncycastle/pqc/") ||
                        (it.startsWith("META-INF/versions/") &&
                            "/org/bouncycastle/pqc/" in it)
                },
            )
            assertTrue(
                "com/minekube/connect/tunnel/p2p/DirectP2pNodeRuntime.class" in
                    payloadEntries,
            )
        }
    }

    @Test
    fun `artifact stays within the adoption download budget`() {
        val bytes = Files.size(artifact())

        assertTrue(
            bytes <= MAX_ARTIFACT_BYTES,
            "Connect Share artifact is $bytes bytes; budget is " +
                "$MAX_ARTIFACT_BYTES bytes",
        )
    }

    @Test
    fun `minecraft profile mapper preserves Mojang Guava ABI`() {
        JarFile(artifact().toFile()).use { jar ->
            val factory = jar.getJarEntry(
                "com/minekube/connect/share/fabric/v26_2/" +
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
    fun `mixin redirects the two argument 262 publish overload`() {
        JarFile(artifact().toFile()).use { jar ->
            val mixin = jar.getJarEntry(
                "com/minekube/connect/share/fabric/v26_2/mixin/" +
                    "IntegratedServerMixin.class",
            )
            assertNotNull(mixin)
            val bytecode = jar.getInputStream(mixin).use {
                it.readBytes().toString(Charsets.ISO_8859_1)
            }
            assertTrue(
                "publishServer(Lnet/minecraft/server/MinecraftServer\$MultiplayerScope;I)Z" in
                    bytecode,
            )
            assertFalse(
                "publishServer(Lnet/minecraft/server/MinecraftServer\$MultiplayerScope;" +
                    "Lnet/minecraft/world/level/GameType;ZI)Z" in
                    bytecode,
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
    fun `packaged runtime starts two peers and inspects a published world`() {
        URLClassLoader(
            arrayOf(artifact().toUri().toURL()),
            ClassLoader.getPlatformClassLoader(),
        ).use { artifactLoader ->
            val loaderType = Class.forName(
                "com.minekube.connect.tunnel.p2p.Libp2pRuntimeLoader",
                true,
                artifactLoader,
            )
            val nodeType = Class.forName(
                "com.minekube.connect.tunnel.p2p.DirectP2pNode",
                true,
                artifactLoader,
            )
            val configType = Class.forName(
                "com.minekube.connect.tunnel.p2p.DirectP2pHostConfig",
                true,
                artifactLoader,
            )
            val handlerType = Class.forName(
                "com.minekube.connect.tunnel.p2p.DirectP2pHostHandler",
                true,
                artifactLoader,
            )
            val hostInfoType = Class.forName(
                "com.minekube.connect.tunnel.p2p.DirectP2pHostInfo",
                true,
                artifactLoader,
            )
            val discoveredType = Class.forName(
                "com.minekube.connect.tunnel.p2p.DirectP2pDiscoveredShare",
                true,
                artifactLoader,
            )
            val host = nodeType.getDeclaredConstructor().newInstance()
            val guest = nodeType.getDeclaredConstructor().newInstance()
            try {
                val config = configType.getDeclaredConstructor(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                ).newInstance(
                    "packaged-share",
                    "packaged-capability-123456789",
                    "Packaged world",
                    false,
                )
                val handler = Proxy.newProxyInstance(
                    artifactLoader,
                    arrayOf(handlerType),
                ) { _, _, _ -> java.net.Socket() }
                val hostInfo = nodeType.getMethod(
                    "startHost",
                    configType,
                    handlerType,
                ).invoke(host, config, handler)
                nodeType.getMethod("publish", String::class.java).invoke(
                    host,
                    "minekube://share/packaged-runtime",
                )
                @Suppress("UNCHECKED_CAST")
                val lanAddresses = hostInfoType.getMethod("lanAddresses")
                    .invoke(hostInfo) as List<String>
                assertTrue(lanAddresses.isNotEmpty())

                val discovered = nodeType.getMethod(
                    "inspect",
                    String::class.java,
                    Duration::class.java,
                ).invoke(
                    guest,
                    lanAddresses.first(),
                    Duration.ofSeconds(3),
                )
                assertTrue(
                    discoveredType.getMethod("displayName")
                        .invoke(discovered) == "Packaged world",
                )
            } finally {
                nodeType.getMethod("close").invoke(guest)
                nodeType.getMethod("close").invoke(host)
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
                it.name.startsWith("connect-share-fabric-26.2-") &&
                    it.name.endsWith(".jar") &&
                    !it.name.contains("sources") &&
                    !it.name.contains("dev")
            }.findFirst().orElse(null)
        }.also(::assertNotNull)
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES = 63L * 1024L * 1024L
        val FORBIDDEN_TYPE_PREFIXES = listOf(
            "io.libp2p.",
            "io.netty.",
        )
    }
}
