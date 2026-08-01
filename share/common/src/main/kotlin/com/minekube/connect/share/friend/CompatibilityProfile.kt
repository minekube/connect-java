package com.minekube.connect.share.friend

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class ModLoader {
    FABRIC,
    NEOFORGE,
    FORGE,
}

data class RequiredMod(
    val id: String,
    val version: String,
) {
    init {
        require(id.isNotBlank()) { "Mod id cannot be blank" }
        require(version.isNotBlank()) { "Mod version cannot be blank" }
    }
}

enum class PackPlatform {
    MODRINTH,
    CURSEFORGE,
    OTHER,
}

data class PackReference(
    val platform: PackPlatform,
    val projectId: String,
    val versionId: String,
    val url: String,
)

data class CompatibilityProfile(
    val minecraftVersion: String,
    val loader: ModLoader,
    val requiredMods: List<RequiredMod>,
    val pack: PackReference? = null,
) {
    init {
        require(minecraftVersion.isNotBlank()) {
            "Minecraft version cannot be blank"
        }
    }

    fun fingerprint(): String = MessageDigest
        .getInstance("SHA-256")
        .digest(canonical().toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    fun compareTo(remote: CompatibilityProfile): CompatibilityReport {
        val differences = buildList {
            if (minecraftVersion != remote.minecraftVersion) {
                add(
                    CompatibilityDifference.MinecraftVersion(
                        local = minecraftVersion,
                        remote = remote.minecraftVersion,
                    ),
                )
            }
            if (loader != remote.loader) {
                add(
                    CompatibilityDifference.Loader(
                        local = loader,
                        remote = remote.loader,
                    ),
                )
            }

            val localMods = normalizedMods()
            val remoteMods = remote.normalizedMods()
            (remoteMods.keys - localMods.keys).sorted().forEach { modId ->
                add(
                    CompatibilityDifference.MissingLocal(
                        modId,
                        remoteMods.getValue(modId),
                    ),
                )
            }
            (localMods.keys - remoteMods.keys).sorted().forEach { modId ->
                add(
                    CompatibilityDifference.MissingRemote(
                        modId,
                        localMods.getValue(modId),
                    ),
                )
            }
            (localMods.keys intersect remoteMods.keys).sorted().forEach { modId ->
                val localVersion = localMods.getValue(modId)
                val remoteVersion = remoteMods.getValue(modId)
                if (localVersion != remoteVersion) {
                    add(
                        CompatibilityDifference.ModVersion(
                            modId = modId,
                            local = localVersion,
                            remote = remoteVersion,
                        ),
                    )
                }
            }
        }
        return if (differences.isEmpty()) {
            CompatibilityReport.Compatible
        } else {
            CompatibilityReport.Mismatch(differences, remote.pack)
        }
    }

    private fun canonical(): String = buildString {
        append(minecraftVersion.trim())
        append('\n')
        append(loader.name)
        normalizedMods().forEach { (id, version) ->
            append('\n')
            append(id)
            append('=')
            append(version)
        }
    }

    private fun normalizedMods(): Map<String, String> = requiredMods
        .associate { mod ->
            mod.id.trim().lowercase() to mod.version.trim()
        }
        .toSortedMap()
}

sealed interface CompatibilityReport {
    data object Compatible : CompatibilityReport

    data class Mismatch(
        val differences: List<CompatibilityDifference>,
        val pack: PackReference? = null,
    ) : CompatibilityReport {
        val hasHardBlock: Boolean = differences.any {
            it is CompatibilityDifference.MinecraftVersion ||
                it is CompatibilityDifference.Loader
        }

        val safeMessage: String = when {
            differences.any { it is CompatibilityDifference.MinecraftVersion } ->
                "Your Minecraft versions do not match."
            differences.any { it is CompatibilityDifference.Loader } ->
                "Your mod loaders do not match."
            else -> "Your required mods do not match."
        }
    }
}

sealed interface CompatibilityDifference {
    data class MinecraftVersion(
        val local: String,
        val remote: String,
    ) : CompatibilityDifference

    data class Loader(
        val local: ModLoader,
        val remote: ModLoader,
    ) : CompatibilityDifference

    data class MissingLocal(
        val modId: String,
        val remoteVersion: String,
    ) : CompatibilityDifference

    data class MissingRemote(
        val modId: String,
        val localVersion: String,
    ) : CompatibilityDifference

    data class ModVersion(
        val modId: String,
        val local: String,
        val remote: String,
    ) : CompatibilityDifference
}
