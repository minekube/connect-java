package com.minekube.connect.share.friend

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

data class PresencePrivacy(
    val showOnline: Boolean = true,
    val showPlaying: Boolean = true,
    val showCurrentServer: Boolean = true,
    val showJoinable: Boolean = true,
)

data class SharePreferences(
    val shareWithFriends: Boolean = false,
    val presence: PresencePrivacy = PresencePrivacy(),
)

class SharePreferencesStore(
    private val directory: Path,
) {
    @Synchronized
    fun load(): SharePreferences {
        Files.createDirectories(directory)
        if (!Files.exists(preferencesFile)) {
            return SharePreferences()
        }
        try {
            val json = GSON.fromJson(
                Files.readString(preferencesFile),
                JsonObject::class.java,
            ) ?: throw IOException("Share preferences are empty")
            val version = json.requiredInt("version")
            if (version !in MIN_WIRE_VERSION..WIRE_VERSION) {
                throw IOException("Share preferences version is unsupported")
            }
            return SharePreferences(
                shareWithFriends = json.requiredBoolean("shareWithFriends"),
                presence = if (version >= 2) {
                    val presence = json.getAsJsonObject("presence")
                        ?: throw IOException(
                            "Share preferences are missing presence privacy",
                        )
                    PresencePrivacy(
                        showOnline = presence.requiredBoolean("showOnline"),
                        showPlaying = presence.requiredBoolean("showPlaying"),
                        showCurrentServer = presence.requiredBoolean(
                            "showCurrentServer",
                        ),
                        showJoinable = presence.requiredBoolean("showJoinable"),
                    )
                } else {
                    PresencePrivacy()
                },
            )
        } catch (exception: JsonParseException) {
            throw IOException("Share preferences are invalid JSON", exception)
        } catch (exception: IllegalStateException) {
            throw IOException("Share preferences are invalid", exception)
        }
    }

    @Synchronized
    fun save(preferences: SharePreferences) {
        Files.createDirectories(directory)
        val json = JsonObject().apply {
            addProperty("version", WIRE_VERSION)
            addProperty("shareWithFriends", preferences.shareWithFriends)
            add("presence", JsonObject().apply {
                addProperty("showOnline", preferences.presence.showOnline)
                addProperty("showPlaying", preferences.presence.showPlaying)
                addProperty(
                    "showCurrentServer",
                    preferences.presence.showCurrentServer,
                )
                addProperty("showJoinable", preferences.presence.showJoinable)
            })
        }
        val temporary = Files.createTempFile(
            directory,
            "$FILE_NAME.",
            ".tmp",
        )
        try {
            val bytes = GSON.toJson(json).toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                val remaining = ByteBuffer.wrap(bytes)
                while (remaining.hasRemaining()) {
                    channel.write(remaining)
                }
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    preferencesFile,
                    ATOMIC_MOVE,
                    REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, preferencesFile, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt
            ?: throw IOException("Share preferences are missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
            ?: throw IOException("Share preferences are missing $name")

    private val preferencesFile: Path
        get() = directory.resolve(FILE_NAME)

    companion object {
        const val FILE_NAME = "share-preferences.json"
        private const val MIN_WIRE_VERSION = 1
        private const val WIRE_VERSION = 2
        private val GSON = Gson()
    }
}
