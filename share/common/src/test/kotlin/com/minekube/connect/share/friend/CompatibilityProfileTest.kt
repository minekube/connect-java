package com.minekube.connect.share.friend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompatibilityProfileTest {
    @Test
    fun `matching profiles are compatible regardless of mod ordering`() {
        val local = profile(
            mods = listOf(
                RequiredMod("fabric-api", "1.0"),
                RequiredMod("example", "2.0"),
            ),
        )
        val remote = profile(mods = local.requiredMods.reversed())

        assertEquals(CompatibilityReport.Compatible, local.compareTo(remote))
        assertEquals(local.fingerprint(), remote.fingerprint())
    }

    @Test
    fun `minecraft loader missing mod and version differences are distinct`() {
        val local = profile(
            minecraft = "1.21.1",
            loader = ModLoader.FABRIC,
            mods = listOf(
                RequiredMod("shared", "1.0"),
                RequiredMod("local-only", "3.0"),
            ),
        )
        val remote = profile(
            minecraft = "1.20.1",
            loader = ModLoader.NEOFORGE,
            mods = listOf(
                RequiredMod("shared", "2.0"),
                RequiredMod("remote-only", "4.0"),
            ),
        )

        val mismatch = assertIs<CompatibilityReport.Mismatch>(
            local.compareTo(remote),
        )

        assertTrue(mismatch.differences.any {
            it is CompatibilityDifference.MinecraftVersion
        })
        assertTrue(mismatch.differences.any {
            it is CompatibilityDifference.Loader
        })
        assertTrue(mismatch.differences.any {
            it is CompatibilityDifference.MissingLocal &&
                it.modId == "remote-only"
        })
        assertTrue(mismatch.differences.any {
            it is CompatibilityDifference.MissingRemote &&
                it.modId == "local-only"
        })
        assertTrue(mismatch.differences.any {
            it is CompatibilityDifference.ModVersion &&
                it.modId == "shared"
        })
    }

    @Test
    fun `pack link is carried but excluded from compatibility fingerprint`() {
        val first = profile().copy(
            pack = PackReference(
                platform = PackPlatform.MODRINTH,
                projectId = "pack",
                versionId = "one",
                url = "https://modrinth.com/modpack/pack/version/one",
            ),
        )
        val second = first.copy(
            pack = first.pack?.copy(versionId = "two"),
        )

        assertEquals(first.fingerprint(), second.fingerprint())
    }

    private fun profile(
        minecraft: String = "1.21.1",
        loader: ModLoader = ModLoader.FABRIC,
        mods: List<RequiredMod> = listOf(RequiredMod("connect-share", "1")),
    ) = CompatibilityProfile(minecraft, loader, mods)
}
