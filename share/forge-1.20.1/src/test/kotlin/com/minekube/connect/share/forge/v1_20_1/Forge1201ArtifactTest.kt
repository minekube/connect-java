package com.minekube.connect.share.forge.v1_20_1

import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Forge1201ArtifactTest {
    @Test
    fun `artifact declares Forge client metadata and mixins`() {
        val artifact = Path.of(checkNotNull(System.getProperty("connectShareArtifact")))
        JarFile(artifact.toFile()).use { jar ->
            val metadata = jar.getInputStream(
                assertNotNull(jar.getJarEntry("META-INF/mods.toml")),
            ).bufferedReader().readText()
            assertTrue("modId=\"connect_share\"" in metadata)
            assertTrue("modId=\"kotlinforforge\"" in metadata)
            val mixinConfig = jar.getInputStream(
                assertNotNull(
                    jar.getJarEntry("connect-share-forge-1.20.1.mixins.json"),
                ),
            ).bufferedReader().readText()
            assertTrue(
                "\"refmap\": \"connect-share-forge-1.20.1.refmap.json\"" in
                    mixinConfig,
            )
            assertNotNull(
                jar.getJarEntry("connect-share-forge-1.20.1.refmap.json"),
            )
            assertNotNull(jar.getJarEntry("pack.mcmeta"))
            assertNotNull(jar.getJarEntry("META-INF/connect/libp2p-runtime.jar"))
            assertNotNull(
                jar.getJarEntry(
                    "com/minekube/connect/share/forge/v1_20_1/" +
                        "ForgeFriendCardNetworking.class",
                ),
            )
            assertEquals(
                "connect-share-forge-1.20.1.mixins.json",
                jar.manifest.mainAttributes.getValue("MixinConfigs"),
            )
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertFalse(names.any { it.startsWith("io/libp2p/") })
            assertFalse(names.any { it.startsWith("io/netty/") })
            assertFalse(names.any { it.startsWith("kotlin/") })
            val entry = assertNotNull(
                jar.getJarEntry(
                    "com/minekube/connect/share/forge/v1_20_1/" +
                        "ForgeConnectShare1201Client.class",
                ),
            )
            val header = jar.getInputStream(entry).readNBytes(8)
            val major = (header[6].toInt() and 0xff) shl 8 or
                (header[7].toInt() and 0xff)
            assertEquals(61, major, "Forge 1.20.1 must remain Java 17 compatible")
        }
    }
}
