package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.ModLoader
import com.minekube.connect.share.friend.PackPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoadedCompatibilityProfileFactoryTest {
    @Test
    fun `profile contains only universal or server gameplay mods`() {
        val profile = LoadedCompatibilityProfileFactory.create(
            minecraftVersion = "1.21.1",
            loader = ModLoader.FABRIC,
            mods = listOf(
                LoadedMod("minecraft", "1.21.1", ModSide.UNIVERSAL, true),
                LoadedMod("fabricloader", "0.16", ModSide.UNIVERSAL),
                LoadedMod("connect-share", "1", ModSide.CLIENT),
                LoadedMod("sodium", "1", ModSide.CLIENT),
                LoadedMod("world-mod", "2", ModSide.UNIVERSAL),
                LoadedMod("server-rules", "3", ModSide.SERVER),
            ),
        )

        assertEquals(ModLoader.FABRIC, profile.loader)
        assertEquals(
            listOf("server-rules", "world-mod"),
            profile.requiredMods.map { it.id },
        )
    }

    @Test
    fun `optional Modrinth pack metadata becomes a recovery link`() {
        val profile = LoadedCompatibilityProfileFactory.create(
            minecraftVersion = "1.21.1",
            loader = ModLoader.FABRIC,
            mods = emptyList(),
            packEnvironment = mapOf(
                "CONNECT_SHARE_PACK_URL" to
                    "https://modrinth.com/modpack/adventure/version/v4",
                "CONNECT_SHARE_PACK_PROJECT" to "adventure",
                "CONNECT_SHARE_PACK_VERSION" to "v4",
            ),
        )

        assertEquals(PackPlatform.MODRINTH, profile.pack?.platform)
        assertEquals("adventure", profile.pack?.projectId)
        assertEquals("v4", profile.pack?.versionId)
    }

    @Test
    fun `CurseForge pack metadata becomes a safe recovery link`() {
        val profile = LoadedCompatibilityProfileFactory.create(
            minecraftVersion = "1.20.1",
            loader = ModLoader.FORGE,
            mods = emptyList(),
            packEnvironment = mapOf(
                "CONNECT_SHARE_PACK_URL" to
                    "https://www.curseforge.com/minecraft/modpacks/adventure/files/7",
                "CONNECT_SHARE_PACK_PROJECT" to "adventure",
                "CONNECT_SHARE_PACK_VERSION" to "7",
            ),
        )

        assertEquals(PackPlatform.CURSEFORGE, profile.pack?.platform)
        assertEquals(
            "https://www.curseforge.com/minecraft/modpacks/adventure/files/7",
            profile.pack?.url,
        )
    }

    @Test
    fun `unsafe pack metadata is never exposed as a recovery link`() {
        listOf(
            "http://modrinth.com/modpack/adventure",
            "https://user:password@modrinth.com/modpack/adventure",
            "file:///tmp/adventure.mrpack",
        ).forEach { url ->
            val profile = LoadedCompatibilityProfileFactory.create(
                minecraftVersion = "1.21.1",
                loader = ModLoader.FABRIC,
                mods = emptyList(),
                packEnvironment = mapOf(
                    "CONNECT_SHARE_PACK_URL" to url,
                    "CONNECT_SHARE_PACK_PROJECT" to "adventure",
                    "CONNECT_SHARE_PACK_VERSION" to "v4",
                ),
            )

            assertNull(profile.pack)
        }
    }
}
