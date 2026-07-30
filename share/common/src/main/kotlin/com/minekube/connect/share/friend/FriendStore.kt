package com.minekube.connect.share.friend

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInviteError
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
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.Base64
import java.util.EnumSet
import java.util.UUID

data class FriendPermissions(
    val notifyWhenOnline: Boolean = true,
    val canSeeMyWorlds: Boolean = true,
    val canJoinAutomatically: Boolean = false,
)

data class SavedFriend(
    val peerId: String,
    val publicKeyBase64: String,
    val shareId: UUID,
    val capability: String,
    val connectAddress: String?,
    val displayName: String,
    val permissions: FriendPermissions = FriendPermissions(),
) {
    override fun toString(): String =
        "SavedFriend(peerId=$peerId, publicKey=<redacted>, " +
            "shareId=$shareId, capability=<redacted>, " +
            "connectAddress=$connectAddress, displayName=$displayName, " +
            "permissions=$permissions)"
}

sealed interface FriendStoreError {
    val safeMessage: String

    data class InvalidInvitation(
        val reason: ShareInviteError,
    ) : FriendStoreError {
        override val safeMessage: String = reason.safeMessage
    }

    data object InvalidDisplayName : FriendStoreError {
        override val safeMessage = "Friend name must be between 1 and 64 characters"
    }

    data object IdentityConflict : FriendStoreError {
        override val safeMessage =
            "This friend identity does not match the previously saved key"
    }

    data object NotFound : FriendStoreError {
        override val safeMessage = "This friend is no longer saved"
    }
}

class FriendStore(
    private val directory: Path,
) {
    @Synchronized
    fun all(): List<SavedFriend> = read()

    @Synchronized
    fun accept(
        invitationUri: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): Either<FriendStoreError, SavedFriend> = either {
        val invite = ShareInviteCodec.decode(invitationUri.trim(), now)
            .mapLeft(FriendStoreError::InvalidInvitation)
            .bind()
        val normalizedName = displayName.trim()
        ensure(normalizedName.length in 1..MAX_DISPLAY_NAME_LENGTH) {
            FriendStoreError.InvalidDisplayName
        }

        val current = read()
        val publicKey = Base64.getEncoder().encodeToString(invite.publicKey)
        val existing = current.firstOrNull {
            it.peerId == invite.payload.peerId
        }
        ensure(existing == null || existing.publicKeyBase64 == publicKey) {
            FriendStoreError.IdentityConflict
        }
        val friend = SavedFriend(
            peerId = invite.payload.peerId,
            publicKeyBase64 = publicKey,
            shareId = invite.payload.shareId,
            capability = invite.payload.capability,
            connectAddress = invite.payload.connectAddress,
            displayName = existing?.displayName ?: normalizedName,
            permissions = existing?.permissions ?: FriendPermissions(),
        )
        write(
            current.filterNot { it.peerId == friend.peerId } + friend,
        )
        friend
    }

    @Synchronized
    fun rename(
        peerId: String,
        displayName: String,
    ): Either<FriendStoreError, SavedFriend> = update(peerId) { friend ->
        val normalized = displayName.trim()
        ensure(normalized.length in 1..MAX_DISPLAY_NAME_LENGTH) {
            FriendStoreError.InvalidDisplayName
        }
        friend.copy(displayName = normalized)
    }

    @Synchronized
    fun updatePermissions(
        peerId: String,
        permissions: FriendPermissions,
    ): Either<FriendStoreError, SavedFriend> = update(peerId) { friend ->
        friend.copy(permissions = permissions)
    }

    @Synchronized
    fun remove(peerId: String): Boolean {
        val current = read()
        val remaining = current.filterNot { it.peerId == peerId }
        if (remaining.size == current.size) {
            return false
        }
        write(remaining)
        return true
    }

    private fun update(
        peerId: String,
        transform:
            arrow.core.raise.Raise<FriendStoreError>.(SavedFriend) -> SavedFriend,
    ): Either<FriendStoreError, SavedFriend> = either {
        val current = read()
        val existing = current.firstOrNull { it.peerId == peerId }
        ensure(existing != null) { FriendStoreError.NotFound }
        val updated = transform(existing)
        write(current.map { if (it.peerId == peerId) updated else it })
        updated
    }

    private fun read(): List<SavedFriend> {
        Files.createDirectories(directory)
        if (!Files.exists(friendsFile)) {
            return emptyList()
        }
        try {
            val root = GSON.fromJson(
                Files.readString(friendsFile),
                JsonObject::class.java,
            ) ?: throw IOException("Friends file is empty")
            if (root.requiredInt("version") != WIRE_VERSION) {
                throw IOException("Friends file version is unsupported")
            }
            val entries = root.getAsJsonArray("friends")
                ?: throw IOException("Friends file is missing friends")
            val friends = entries.map { element ->
                parseFriend(element.asJsonObject)
            }
            if (friends.size > MAX_FRIENDS) {
                throw IOException("Friends file contains too many entries")
            }
            if (friends.map(SavedFriend::peerId).distinct().size != friends.size) {
                throw IOException("Friends file contains duplicate identities")
            }
            return friends
        } catch (exception: JsonParseException) {
            throw IOException("Friends file is invalid JSON", exception)
        } catch (exception: IllegalStateException) {
            throw IOException("Friends file is invalid", exception)
        } catch (exception: IllegalArgumentException) {
            throw IOException("Friends file contains invalid data", exception)
        }
    }

    private fun parseFriend(json: JsonObject): SavedFriend {
        val peerId = json.requiredString("peerId")
        val publicKey = json.requiredString("publicKey")
        val shareId = UUID.fromString(json.requiredString("shareId"))
        val capability = json.requiredString("capability")
        val connectAddress = json.optionalString("connectAddress")
        val displayName = json.requiredString("displayName")
        if (
            peerId.isBlank() ||
            publicKey.isBlank() ||
            !isValidCapability(capability) ||
            displayName.trim().length !in 1..MAX_DISPLAY_NAME_LENGTH
        ) {
            throw IOException("Friends file contains an invalid friend")
        }
        Base64.getDecoder().decode(publicKey)
        val permissions = json.getAsJsonObject("permissions")
            ?: throw IOException("Friends file is missing permissions")
        return SavedFriend(
            peerId = peerId,
            publicKeyBase64 = publicKey,
            shareId = shareId,
            capability = capability,
            connectAddress = connectAddress,
            displayName = displayName,
            permissions = FriendPermissions(
                notifyWhenOnline = permissions.requiredBoolean("notifyWhenOnline"),
                canSeeMyWorlds = permissions.requiredBoolean("canSeeMyWorlds"),
                canJoinAutomatically =
                    permissions.requiredBoolean("canJoinAutomatically"),
            ),
        )
    }

    private fun write(friends: List<SavedFriend>) {
        require(friends.size <= MAX_FRIENDS) {
            "Connect Share supports at most $MAX_FRIENDS saved friends"
        }
        Files.createDirectories(directory)
        val entries = JsonArray()
        friends.sortedBy { it.displayName.lowercase() }.forEach { friend ->
            entries.add(JsonObject().apply {
                addProperty("peerId", friend.peerId)
                addProperty("publicKey", friend.publicKeyBase64)
                addProperty("shareId", friend.shareId.toString())
                addProperty("capability", friend.capability)
                friend.connectAddress?.let {
                    addProperty("connectAddress", it)
                }
                addProperty("displayName", friend.displayName)
                add(
                    "permissions",
                    JsonObject().apply {
                        addProperty(
                            "notifyWhenOnline",
                            friend.permissions.notifyWhenOnline,
                        )
                        addProperty(
                            "canSeeMyWorlds",
                            friend.permissions.canSeeMyWorlds,
                        )
                        addProperty(
                            "canJoinAutomatically",
                            friend.permissions.canJoinAutomatically,
                        )
                    },
                )
            })
        }
        val root = JsonObject().apply {
            addProperty("version", WIRE_VERSION)
            add("friends", entries)
        }
        writeAtomic(GSON.toJson(root))
    }

    private fun writeAtomic(content: String) {
        val temporary = Files.createTempFile(directory, "$FILE_NAME.", ".tmp")
        try {
            setOwnerOnlyPermissions(temporary)
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                val remaining = ByteBuffer.wrap(bytes)
                while (remaining.hasRemaining()) {
                    channel.write(remaining)
                }
                channel.force(true)
            }
            try {
                Files.move(temporary, friendsFile, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, friendsFile, REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(friendsFile)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun setOwnerOnlyPermissions(file: Path) {
        try {
            Files.setPosixFilePermissions(
                file,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems do not expose Unix file modes.
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString
            ?: throw IOException("Friends file is missing $name")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt
            ?: throw IOException("Friends file is missing $name")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
            ?: throw IOException("Friends file is missing $name")

    private val friendsFile: Path
        get() = directory.resolve(FILE_NAME)

    companion object {
        const val FILE_NAME = "friends.json"
        private const val WIRE_VERSION = 1
        private const val MAX_FRIENDS = 256
        private const val MAX_DISPLAY_NAME_LENGTH = 64
        private val GSON = Gson()

        private fun isValidCapability(value: String): Boolean =
            value.length in 16..512 &&
                value.none(Char::isWhitespace)
    }
}
