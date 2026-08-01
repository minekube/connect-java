package com.minekube.connect.share.friend

import arrow.core.Either
import arrow.core.Option
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.toOption
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

enum class FriendAccessPolicy {
    ASK_EVERY_TIME,
    AUTO_ACCEPT,
    NEVER_ALLOW,
}

data class FriendPermissions(
    val notifyWhenOnline: Boolean = true,
    val canSeeMyWorlds: Boolean = true,
    val accessPolicy: FriendAccessPolicy = FriendAccessPolicy.ASK_EVERY_TIME,
) {
    val canJoinAutomatically: Boolean
        get() = accessPolicy == FriendAccessPolicy.AUTO_ACCEPT

    constructor(
        notifyWhenOnline: Boolean = true,
        canSeeMyWorlds: Boolean = true,
        canJoinAutomatically: Boolean,
    ) : this(
        notifyWhenOnline = notifyWhenOnline,
        canSeeMyWorlds = canSeeMyWorlds,
        accessPolicy = if (canJoinAutomatically) {
            FriendAccessPolicy.AUTO_ACCEPT
        } else {
            FriendAccessPolicy.ASK_EVERY_TIME
        },
    )
}

enum class FriendRelationshipStatus {
    PENDING_OUTGOING,
    CONFIRMED,
}

data class SavedFriend(
    val peerId: String,
    val publicKeyBase64: String,
    val shareId: UUID,
    val capability: String,
    val connectAddress: String?,
    val internetDirectEnabled: Boolean = false,
    val directCandidates: List<String> = emptyList(),
    val displayName: String,
    val minecraftUuid: UUID? = null,
    val permissions: FriendPermissions = FriendPermissions(),
    val relationshipStatus: FriendRelationshipStatus =
        FriendRelationshipStatus.CONFIRMED,
) {
    override fun toString(): String =
        "SavedFriend(peerId=$peerId, publicKey=<redacted>, " +
            "shareId=$shareId, capability=<redacted>, " +
            "connectAddress=$connectAddress, directCandidates=<redacted>, " +
            "displayName=$displayName, " +
            "minecraftUuid=$minecraftUuid, permissions=$permissions, " +
            "relationshipStatus=$relationshipStatus)"
}

data class PendingFriendRemoval(
    val operationId: UUID,
    val friend: SavedFriend,
    val removedAt: Instant,
)

data class BlockedFriend(
    val peerId: String,
    val publicKeyBase64: String,
    val displayName: String,
    val blockedAt: Instant,
) {
    override fun toString(): String =
        "BlockedFriend(peerId=$peerId, publicKey=<redacted>, " +
            "displayName=$displayName, blockedAt=$blockedAt)"
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

    data object Blocked : FriendStoreError {
        override val safeMessage =
            "This identity is blocked. Unblock it before adding it again"
    }
}

class FriendStore(
    private val directory: Path,
) {
    private var cached: StoreData? = null

    @Synchronized
    fun all(): List<SavedFriend> =
        read().filter {
            it.relationshipStatus == FriendRelationshipStatus.CONFIRMED
        }

    @Synchronized
    fun outgoingRequests(): List<SavedFriend> =
        read().filter {
            it.relationshipStatus ==
                FriendRelationshipStatus.PENDING_OUTGOING
        }

    @Synchronized
    fun relationship(peerId: String): Option<SavedFriend> =
        read().firstOrNull { it.peerId == peerId }.toOption()

    @Synchronized
    fun pendingRemovals(): List<PendingFriendRemoval> =
        data().removals

    @Synchronized
    fun blocked(): List<BlockedFriend> = data().blocked

    @Synchronized
    fun isBlocked(peerId: String): Boolean =
        data().blocked.any { it.peerId == peerId }

    @Synchronized
    fun accept(
        invitationUri: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): Either<FriendStoreError, SavedFriend> =
        storeInvitation(
            invitationUri = invitationUri,
            displayName = displayName,
            relationshipStatus = FriendRelationshipStatus.CONFIRMED,
            now = now,
        )

    @Synchronized
    fun acceptAndAllowJoin(
        invitationUri: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): Either<FriendStoreError, SavedFriend> =
        storeInvitation(
            invitationUri = invitationUri,
            displayName = displayName,
            relationshipStatus = FriendRelationshipStatus.CONFIRMED,
            allowAutomaticJoin = true,
            now = now,
        )

    @Synchronized
    fun sendRequest(
        invitationUri: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): Either<FriendStoreError, SavedFriend> =
        storeInvitation(
            invitationUri = invitationUri,
            displayName = displayName,
            relationshipStatus =
                FriendRelationshipStatus.PENDING_OUTGOING,
            now = now,
        )

    @Synchronized
    fun confirmOutgoing(
        peerId: String,
    ): Either<FriendStoreError, SavedFriend> = update(peerId) { friend ->
        friend.copy(
            relationshipStatus = FriendRelationshipStatus.CONFIRMED,
        )
    }

    private fun storeInvitation(
        invitationUri: String,
        displayName: String,
        relationshipStatus: FriendRelationshipStatus,
        allowAutomaticJoin: Boolean = false,
        now: Instant,
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
        ensure(data().blocked.none { it.peerId == invite.payload.peerId }) {
            FriendStoreError.Blocked
        }
        val existing = current.firstOrNull {
            it.peerId == invite.payload.peerId
        }
        ensure(existing == null || existing.publicKeyBase64 == publicKey) {
            FriendStoreError.IdentityConflict
        }
        val effectiveRelationshipStatus = when {
            existing?.relationshipStatus ==
                FriendRelationshipStatus.CONFIRMED ->
                FriendRelationshipStatus.CONFIRMED

            else -> relationshipStatus
        }
        val friend = SavedFriend(
            peerId = invite.payload.peerId,
            publicKeyBase64 = publicKey,
            shareId = invite.payload.shareId,
            capability = invite.payload.capability,
            connectAddress = invite.payload.connectAddress,
            internetDirectEnabled = invite.payload.internetDirectEnabled,
            directCandidates = invite.payload.directCandidates,
            displayName = existing?.displayName ?: normalizedName,
            minecraftUuid = existing?.minecraftUuid,
            permissions = (existing?.permissions ?: FriendPermissions())
                .let { permissions ->
                    if (allowAutomaticJoin) {
                        permissions.copy(
                            accessPolicy = FriendAccessPolicy.AUTO_ACCEPT,
                        )
                    } else {
                        permissions
                    }
                },
            relationshipStatus = effectiveRelationshipStatus,
        )
        write(
            data().copy(
                friends = current.filterNot {
                    it.peerId == friend.peerId
                } + friend,
                removals = data().removals.filterNot {
                    it.friend.peerId == friend.peerId
                },
            ),
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
    fun linkMinecraftIdentity(
        peerId: String,
        minecraftUuid: UUID,
    ): Either<FriendStoreError, SavedFriend> = update(peerId) { friend ->
        friend.copy(minecraftUuid = minecraftUuid)
    }

    @Synchronized
    fun remove(
        peerId: String,
        now: Instant = Instant.now(),
    ): Boolean {
        val current = read()
        val removed = current.firstOrNull { it.peerId == peerId }
            ?: return false
        val remaining = current.filterNot { it.peerId == peerId }
        val removals = data().removals.filterNot {
            it.friend.peerId == peerId
        } + PendingFriendRemoval(
            operationId = UUID.randomUUID(),
            friend = removed,
            removedAt = now,
        )
        write(StoreData(remaining, removals))
        return true
    }

    @Synchronized
    fun block(
        peerId: String,
        now: Instant = Instant.now(),
    ): Boolean {
        val current = read()
        val blockedFriend = current.firstOrNull { it.peerId == peerId }
            ?: return false
        val removal = PendingFriendRemoval(
            operationId = UUID.randomUUID(),
            friend = blockedFriend,
            removedAt = now,
        )
        val blocked = BlockedFriend(
            peerId = blockedFriend.peerId,
            publicKeyBase64 = blockedFriend.publicKeyBase64,
            displayName = blockedFriend.displayName,
            blockedAt = now,
        )
        write(
            data().copy(
                friends = current.filterNot { it.peerId == peerId },
                removals = data().removals.filterNot {
                    it.friend.peerId == peerId
                } + removal,
                blocked = data().blocked.filterNot {
                    it.peerId == peerId
                } + blocked,
            ),
        )
        return true
    }

    @Synchronized
    fun unblock(peerId: String): Boolean {
        val current = data()
        val remaining = current.blocked.filterNot { it.peerId == peerId }
        if (remaining.size == current.blocked.size) return false
        write(current.copy(blocked = remaining))
        return true
    }

    @Synchronized
    fun applyRemoteRemoval(peerId: String): Boolean {
        val current = read()
        if (current.none { it.peerId == peerId }) {
            return false
        }
        write(data().copy(friends = current.filterNot { it.peerId == peerId }))
        return true
    }

    @Synchronized
    fun acknowledgeRemoval(operationId: UUID): Boolean {
        val current = data()
        val remaining = current.removals.filterNot {
            it.operationId == operationId
        }
        if (remaining.size == current.removals.size) {
            return false
        }
        write(current.copy(removals = remaining))
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

    private fun read(): List<SavedFriend> = data().friends

    private fun data(): StoreData =
        cached ?: load().also { cached = it }

    private fun load(): StoreData {
        Files.createDirectories(directory)
        if (!Files.exists(friendsFile)) {
            return StoreData()
        }
        try {
            val root = GSON.fromJson(
                Files.readString(friendsFile),
                JsonObject::class.java,
            ) ?: throw IOException("Friends file is empty")
            val version = root.requiredInt("version")
            if (version !in MIN_WIRE_VERSION..WIRE_VERSION) {
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
            val removals = if (version >= 2) {
                root.getAsJsonArray("pendingRemovals")
                    ?.map { element -> parseRemoval(element.asJsonObject) }
                    ?: emptyList()
            } else {
                emptyList()
            }
            if (removals.size > MAX_FRIENDS) {
                throw IOException("Friends file contains too many removals")
            }
            val blocked = if (version >= 4) {
                root.getAsJsonArray("blocked")
                    ?.map { element -> parseBlocked(element.asJsonObject) }
                    ?: emptyList()
            } else {
                emptyList()
            }
            if (blocked.size > MAX_FRIENDS) {
                throw IOException("Friends file contains too many blocks")
            }
            return StoreData(friends, removals, blocked)
        } catch (exception: JsonParseException) {
            throw IOException("Friends file is invalid JSON", exception)
        } catch (exception: IllegalStateException) {
            throw IOException("Friends file is invalid", exception)
        } catch (exception: IllegalArgumentException) {
            throw IOException("Friends file contains invalid data", exception)
        }
    }

    private fun parseRemoval(json: JsonObject): PendingFriendRemoval =
        PendingFriendRemoval(
            operationId = UUID.fromString(json.requiredString("operationId")),
            friend = parseFriend(
                json.getAsJsonObject("friend")
                    ?: throw IOException("Removal is missing friend"),
            ),
            removedAt = Instant.ofEpochMilli(
                json.get("removedAtEpochMillis")?.asLong
                    ?: throw IOException("Removal is missing time"),
            ),
        )

    private fun parseBlocked(json: JsonObject): BlockedFriend =
        BlockedFriend(
            peerId = json.requiredString("peerId"),
            publicKeyBase64 = json.requiredString("publicKey").also {
                Base64.getDecoder().decode(it)
            },
            displayName = json.requiredString("displayName"),
            blockedAt = Instant.ofEpochMilli(
                json.get("blockedAtEpochMillis")?.asLong
                    ?: throw IOException("Block is missing time"),
            ),
        )

    private fun parseFriend(json: JsonObject): SavedFriend {
        val peerId = json.requiredString("peerId")
        val publicKey = json.requiredString("publicKey")
        val shareId = UUID.fromString(json.requiredString("shareId"))
        val capability = json.requiredString("capability")
        val connectAddress = json.optionalString("connectAddress")
        val internetDirectEnabled =
            json.optionalBoolean("internetDirectEnabled") ?: false
        val directCandidates = json.getAsJsonArray("directCandidates")
            ?.map { it.asString }
            ?: emptyList()
        val displayName = json.requiredString("displayName")
        val minecraftUuid = json.optionalString("minecraftUuid")
            ?.let(UUID::fromString)
        if (
            peerId.isBlank() ||
            publicKey.isBlank() ||
            !isValidCapability(capability) ||
            directCandidates.size > MAX_DIRECT_CANDIDATES ||
            (!internetDirectEnabled && directCandidates.isNotEmpty()) ||
            directCandidates.any {
                it.isBlank() ||
                    it.length > MAX_DIRECT_CANDIDATE_LENGTH ||
                    it.contains("/p2p-circuit") ||
                    it.contains("/circuit/") ||
                    it.substringAfterLast("/p2p/", "") != peerId
            } ||
            displayName.trim().length !in 1..MAX_DISPLAY_NAME_LENGTH
        ) {
            throw IOException("Friends file contains an invalid friend")
        }
        Base64.getDecoder().decode(publicKey)
        val permissions = json.getAsJsonObject("permissions")
            ?: throw IOException("Friends file is missing permissions")
        val parsedPermissions = FriendPermissions(
            notifyWhenOnline =
                permissions.requiredBoolean("notifyWhenOnline"),
            canSeeMyWorlds =
                permissions.requiredBoolean("canSeeMyWorlds"),
            accessPolicy = permissions.optionalString("accessPolicy")
                ?.let(FriendAccessPolicy::valueOf)
                ?: if (permissions.requiredBoolean("canJoinAutomatically")) {
                    FriendAccessPolicy.AUTO_ACCEPT
                } else {
                    FriendAccessPolicy.ASK_EVERY_TIME
                },
        )
        val relationshipStatus = json
            .optionalString("relationshipStatus")
            ?.let(::parseRelationshipStatus)
            ?: legacyRelationshipStatus(
                minecraftUuid = minecraftUuid,
                permissions = parsedPermissions,
            )
        return SavedFriend(
            peerId = peerId,
            publicKeyBase64 = publicKey,
            shareId = shareId,
            capability = capability,
            connectAddress = connectAddress,
            internetDirectEnabled = internetDirectEnabled,
            directCandidates = directCandidates,
            displayName = displayName,
            minecraftUuid = minecraftUuid,
            permissions = parsedPermissions,
            relationshipStatus = relationshipStatus,
        )
    }

    private fun write(friends: List<SavedFriend>) {
        write(data().copy(friends = friends))
    }

    private fun write(data: StoreData) {
        require(data.friends.size <= MAX_FRIENDS) {
            "Connect Share supports at most $MAX_FRIENDS saved friends"
        }
        require(data.removals.size <= MAX_FRIENDS) {
            "Connect Share supports at most $MAX_FRIENDS pending removals"
        }
        require(data.blocked.size <= MAX_FRIENDS) {
            "Connect Share supports at most $MAX_FRIENDS blocked identities"
        }
        Files.createDirectories(directory)
        val entries = JsonArray()
        data.friends.sortedBy { it.displayName.lowercase() }.forEach { friend ->
            entries.add(friend.toJson())
        }
        val removals = JsonArray()
        data.removals.sortedBy { it.removedAt }.forEach { removal ->
            removals.add(JsonObject().apply {
                addProperty("operationId", removal.operationId.toString())
                addProperty(
                    "removedAtEpochMillis",
                    removal.removedAt.toEpochMilli(),
                )
                add("friend", removal.friend.toJson())
            })
        }
        val root = JsonObject().apply {
            addProperty("version", WIRE_VERSION)
            add("friends", entries)
            add("pendingRemovals", removals)
            add("blocked", JsonArray().apply {
                data.blocked.sortedBy { it.blockedAt }.forEach { blocked ->
                    add(JsonObject().apply {
                        addProperty("peerId", blocked.peerId)
                        addProperty("publicKey", blocked.publicKeyBase64)
                        addProperty("displayName", blocked.displayName)
                        addProperty(
                            "blockedAtEpochMillis",
                            blocked.blockedAt.toEpochMilli(),
                        )
                    })
                }
            })
        }
        writeAtomic(GSON.toJson(root))
        cached = data.copy(
            friends = data.friends.toList(),
            removals = data.removals.toList(),
        )
    }

    private fun SavedFriend.toJson(): JsonObject = JsonObject().apply {
        addProperty("peerId", peerId)
        addProperty("publicKey", publicKeyBase64)
        addProperty("shareId", shareId.toString())
        addProperty("capability", capability)
        connectAddress?.let { addProperty("connectAddress", it) }
        addProperty("internetDirectEnabled", internetDirectEnabled)
        add("directCandidates", JsonArray().apply {
            directCandidates.forEach(::add)
        })
        addProperty("displayName", displayName)
        minecraftUuid?.let { addProperty("minecraftUuid", it.toString()) }
        addProperty("relationshipStatus", relationshipStatus.name)
        add(
            "permissions",
            JsonObject().apply {
                addProperty("notifyWhenOnline", permissions.notifyWhenOnline)
                addProperty("canSeeMyWorlds", permissions.canSeeMyWorlds)
                addProperty("accessPolicy", permissions.accessPolicy.name)
            },
        )
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

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean

    private val friendsFile: Path
        get() = directory.resolve(FILE_NAME)

    companion object {
        const val FILE_NAME = "friends.json"
        private const val MIN_WIRE_VERSION = 1
        private const val WIRE_VERSION = 5
        private const val MAX_FRIENDS = 256
        private const val MAX_DIRECT_CANDIDATES = 4
        private const val MAX_DIRECT_CANDIDATE_LENGTH = 8_192
        private const val MAX_DISPLAY_NAME_LENGTH = 64
        private val GSON = Gson()

        private fun legacyRelationshipStatus(
            minecraftUuid: UUID?,
            permissions: FriendPermissions,
        ): FriendRelationshipStatus =
            if (
                minecraftUuid != null ||
                permissions.canJoinAutomatically
            ) {
                FriendRelationshipStatus.CONFIRMED
            } else {
                FriendRelationshipStatus.PENDING_OUTGOING
            }

        private fun parseRelationshipStatus(
            value: String,
        ): FriendRelationshipStatus =
            if (value == "PENDING_INCOMING") {
                FriendRelationshipStatus.PENDING_OUTGOING
            } else {
                FriendRelationshipStatus.valueOf(value)
            }

        private fun isValidCapability(value: String): Boolean =
            value.length in 16..512 &&
                value.none(Char::isWhitespace)
    }

    private data class StoreData(
        val friends: List<SavedFriend> = emptyList(),
        val removals: List<PendingFriendRemoval> = emptyList(),
        val blocked: List<BlockedFriend> = emptyList(),
    )
}
