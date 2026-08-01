package com.minekube.connect.share.neoforge.v1_21_1

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NeoForge1211ArtifactTest {
    @Test
    fun `artifact declares NeoForge client metadata and mixins`() {
        val artifact = Path.of(checkNotNull(System.getProperty("connectShareArtifact")))
        JarFile(artifact.toFile()).use { jar ->
            val metadata = jar.getInputStream(
                assertNotNull(jar.getJarEntry("META-INF/neoforge.mods.toml")),
            ).bufferedReader().readText()
            assertTrue("modId=\"connect_share\"" in metadata)
            assertTrue("modId=\"kotlinforforge\"" in metadata)
            assertNotNull(
                jar.getJarEntry("connect-share-fabric-1.21.1.mixins.json"),
            )
            assertNotNull(jar.getJarEntry("META-INF/connect/libp2p-runtime.jar"))
            assertNotNull(
                jar.getJarEntry(
                    "com/minekube/connect/share/neoforge/v1_21_1/" +
                        "NeoForgeFriendCardNetworking.class",
                ),
            )
            assertNotNull(jar.getJarEntry("pack.mcmeta"))
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertFalse(names.any { it.startsWith("io/libp2p/") })
            assertFalse(names.any { it.startsWith("io/netty/") })
            assertFalse(names.any { it.startsWith("kotlin/") })
            val entry = assertNotNull(
                jar.getJarEntry(
                    "com/minekube/connect/share/neoforge/v1_21_1/" +
                        "NeoForgeConnectShare1211Client.class",
                ),
            )
            val header = jar.getInputStream(entry).readNBytes(8)
            val major = (header[6].toInt() and 0xff) shl 8 or
                (header[7].toInt() and 0xff)
            assertEquals(65, major, "NeoForge 1.21.1 must remain Java 21 compatible")
        }
    }
}
