package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.friend.CompatibilityProfile
import com.minekube.connect.share.friend.ModLoader
import com.minekube.connect.share.friend.PackPlatform
import com.minekube.connect.share.friend.PackReference
import com.minekube.connect.share.friend.RequiredMod
import java.net.URI

enum class ModSide {
    UNIVERSAL,
    CLIENT,
    SERVER,
}

data class LoadedMod(
    val id: String,
    val version: String,
    val side: ModSide,
    val builtIn: Boolean = false,
)

object LoadedCompatibilityProfileFactory {
    fun create(
        minecraftVersion: String,
        loader: ModLoader,
        mods: Collection<LoadedMod>,
        packEnvironment: Map<String, String> = emptyMap(),
    ): CompatibilityProfile = CompatibilityProfile(
        minecraftVersion = minecraftVersion,
        loader = loader,
        requiredMods = mods.asSequence()
            .filterNot(LoadedMod::builtIn)
            .filter { it.side != ModSide.CLIENT }
            .filterNot { it.id.lowercase() in LOADER_COMPONENT_IDS }
            .filter { it.id.isNotBlank() && it.version.isNotBlank() }
            .map { RequiredMod(it.id, it.version) }
            .distinctBy { it.id.lowercase() }
            .sortedBy { it.id.lowercase() }
            .toList(),
        pack = packReference(packEnvironment),
    )

    private fun packReference(
        environment: Map<String, String>,
    ): PackReference? {
        val rawUrl = environment[PACK_URL_ENV]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val project = environment[PACK_PROJECT_ENV]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val version = environment[PACK_VERSION_ENV]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val uri = Either.catch { URI(rawUrl) }.getOrNull()
            ?.takeIf {
                it.scheme.equals("https", ignoreCase = true) &&
                    !it.host.isNullOrBlank() &&
                    it.userInfo == null
            }
            ?: return null
        val platform = when (uri.host.lowercase()) {
            "modrinth.com", "www.modrinth.com" -> PackPlatform.MODRINTH
            "curseforge.com", "www.curseforge.com" -> PackPlatform.CURSEFORGE
            else -> PackPlatform.OTHER
        }
        return PackReference(
            platform = platform,
            projectId = project,
            versionId = version,
            url = uri.toASCIIString(),
        )
    }

    private val LOADER_COMPONENT_IDS = setOf(
        "java",
        "minecraft",
        "fabricloader",
        "fabric-language-kotlin",
        "forge",
        "neoforge",
    )
    private const val PACK_URL_ENV = "CONNECT_SHARE_PACK_URL"
    private const val PACK_PROJECT_ENV = "CONNECT_SHARE_PACK_PROJECT"
    private const val PACK_VERSION_ENV = "CONNECT_SHARE_PACK_VERSION"
}
