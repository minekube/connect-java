package com.minekube.connect.share.friend

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

data class FriendControlRequest(
    val requestId: UUID,
    val relationshipId: UUID = requestId,
    val displayName: String,
    val invitation: String,
)

data class FriendRemovalRequest(
    val operationId: UUID,
    val relationshipId: UUID = operationId,
)

data class FriendActivityRequest(val requestId: UUID)

data class FriendJoinRequest(
    val requestId: UUID,
    val playerName: String,
    val playerUuid: UUID,
)

enum class FriendActivityKind {
    ONLINE,
    HOSTING_WORLD,
    PLAYING_SERVER,
}

data class FriendActivity(
    val kind: FriendActivityKind,
    val description: String? = null,
    val joinable: Boolean = kind != FriendActivityKind.ONLINE,
    val sessionEpoch: String? = null,
    val compatibility: CompatibilityProfile? = null,
)

enum class FriendControlMessageKind {
    PAIRING,
    REMOVAL,
    ACTIVITY,
    JOIN,
    OTHER,
}

sealed interface FriendControlResponse {
    data object Received : FriendControlResponse

    data class Accepted(
        val invitation: String,
    ) : FriendControlResponse

    data object Declined : FriendControlResponse

    data object TimedOut : FriendControlResponse

    data object Invalid : FriendControlResponse

    data object Removed : FriendControlResponse

    data class Activity(val activity: FriendActivity) : FriendControlResponse

    data class JoinAccepted(val address: String) : FriendControlResponse

    data object SharedWorldJoinAccepted : FriendControlResponse
}

sealed interface FriendControlDecode<out A> {
    data class Decoded<A>(
        val value: A,
        val consumedBytes: Int,
    ) : FriendControlDecode<A>

    data object Incomplete : FriendControlDecode<Nothing>

    data object Invalid : FriendControlDecode<Nothing>
}

object FriendControlWire {
    const val MAX_REQUEST_BYTES = 65_536

    private const val STATUS_INTENTION = 1
    private const val HANDSHAKE_PACKET_ID = 0
    private const val CONTROL_REQUEST_PACKET_ID = 0x43F1
    private const val CONTROL_RESPONSE_PACKET_ID = 0x43F2
    private const val CONTROL_REMOVAL_PACKET_ID = 0x43F3
    private const val CONTROL_ACTIVITY_PACKET_ID = 0x43F4
    private const val CONTROL_JOIN_PACKET_ID = 0x43F5
    private const val MAX_ADDRESS_BYTES = 255
    private const val MAX_DISPLAY_NAME_BYTES = 256
    private const val MAX_INVITATION_BYTES = 32_768
    private const val MAX_ACTIVITY_BYTES = 512
    private const val MAX_SESSION_EPOCH_BYTES = 128
    private const val MAX_SERVER_ADDRESS_BYTES = 1_024
    private const val MAX_PLAYER_NAME_BYTES = 64
    private const val MAX_VERSION_BYTES = 128
    private const val MAX_MOD_ID_BYTES = 256
    private const val MAX_REQUIRED_MODS = 512
    private const val MAX_PACK_FIELD_BYTES = 2_048

    fun encodeRequest(
        request: FriendControlRequest,
    ): ByteArray {
        require(
            request.displayName.trim().isNotEmpty() &&
                request.displayName.toByteArray(StandardCharsets.UTF_8).size <=
                MAX_DISPLAY_NAME_BYTES,
        ) {
            "Friend display name is invalid"
        }
        require(
            request.invitation.toByteArray(StandardCharsets.UTF_8).size <=
                MAX_INVITATION_BYTES,
        ) {
            "Friend invitation is too large"
        }

        val output = ByteArrayOutputStream()
        output.writePacket {
            writeVarInt(CONTROL_REQUEST_PACKET_ID)
            writeLong(request.requestId.mostSignificantBits)
            writeLong(request.requestId.leastSignificantBits)
            writeLong(request.relationshipId.mostSignificantBits)
            writeLong(request.relationshipId.leastSignificantBits)
            writeString(request.displayName.trim())
            writeString(request.invitation)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_REQUEST_BYTES) {
                "Friend request is too large"
            }
        }
    }

    fun decodeRequest(
        bytes: ByteArray,
    ): FriendControlDecode<FriendControlRequest> {
        if (bytes.size > MAX_REQUEST_BYTES) {
            return FriendControlDecode.Invalid
        }
        val current = decode(bytes) {
            val control = readPacket()
            ensure(control.readVarInt() == CONTROL_REQUEST_PACKET_ID)
            val requestId = UUID(
                control.readLong(),
                control.readLong(),
            )
            val relationshipId = UUID(
                control.readLong(),
                control.readLong(),
            )
            val displayName = control
                .readString(MAX_DISPLAY_NAME_BYTES)
                .trim()
            ensure(displayName.isNotEmpty())
            val invitation = control.readString(MAX_INVITATION_BYTES)
            ensure(invitation.isNotEmpty())
            control.ensureFinished()
            FriendControlRequest(
                requestId = requestId,
                relationshipId = relationshipId,
                displayName = displayName,
                invitation = invitation,
            )
        }
        if (current is FriendControlDecode.Decoded) {
            return current
        }
        val legacy = decode(bytes) {
            val control = readPacket()
            ensure(control.readVarInt() == CONTROL_REQUEST_PACKET_ID)
            val requestId = UUID(
                control.readLong(),
                control.readLong(),
            )
            val displayName = control
                .readString(MAX_DISPLAY_NAME_BYTES)
                .trim()
            ensure(displayName.isNotEmpty())
            val invitation = control.readString(MAX_INVITATION_BYTES)
            ensure(invitation.isNotEmpty())
            control.ensureFinished()
            FriendControlRequest(
                requestId = requestId,
                relationshipId = requestId,
                displayName = displayName,
                invitation = invitation,
            )
        }
        return when {
            legacy is FriendControlDecode.Decoded -> legacy
            current is FriendControlDecode.Incomplete ||
                legacy is FriendControlDecode.Incomplete ->
                FriendControlDecode.Incomplete
            else -> FriendControlDecode.Invalid
        }
    }

    fun encodeRemoval(request: FriendRemovalRequest): ByteArray {
        val output = ByteArrayOutputStream()
        output.writePacket {
            writeVarInt(CONTROL_REMOVAL_PACKET_ID)
            writeLong(request.operationId.mostSignificantBits)
            writeLong(request.operationId.leastSignificantBits)
            writeLong(request.relationshipId.mostSignificantBits)
            writeLong(request.relationshipId.leastSignificantBits)
        }
        return output.toByteArray()
    }

    fun decodeRemoval(
        bytes: ByteArray,
    ): FriendControlDecode<FriendRemovalRequest> {
        if (bytes.size > MAX_REQUEST_BYTES) {
            return FriendControlDecode.Invalid
        }
        val current = decode(bytes) {
            val control = readPacket()
            ensure(control.readVarInt() == CONTROL_REMOVAL_PACKET_ID)
            val request = FriendRemovalRequest(
                UUID(control.readLong(), control.readLong()),
                UUID(control.readLong(), control.readLong()),
            )
            control.ensureFinished()
            request
        }
        if (current is FriendControlDecode.Decoded) {
            return current
        }
        val legacy = decode(bytes) {
            val control = readPacket()
            ensure(control.readVarInt() == CONTROL_REMOVAL_PACKET_ID)
            val operationId = UUID(control.readLong(), control.readLong())
            control.ensureFinished()
            FriendRemovalRequest(
                operationId = operationId,
                relationshipId = operationId,
            )
        }
        return when {
            legacy is FriendControlDecode.Decoded -> legacy
            current is FriendControlDecode.Incomplete ||
                legacy is FriendControlDecode.Incomplete ->
                FriendControlDecode.Incomplete
            else -> FriendControlDecode.Invalid
        }
    }

    fun encodeActivityRequest(request: FriendActivityRequest): ByteArray =
        encodeIdRequest(CONTROL_ACTIVITY_PACKET_ID, request.requestId)

    fun decodeActivityRequest(
        bytes: ByteArray,
    ): FriendControlDecode<FriendActivityRequest> =
        decodeIdRequest(bytes, CONTROL_ACTIVITY_PACKET_ID) {
            FriendActivityRequest(it)
        }

    fun encodeJoinRequest(request: FriendJoinRequest): ByteArray {
        val playerName = request.playerName.trim()
        require(
            playerName.isNotEmpty() &&
                playerName.toByteArray(StandardCharsets.UTF_8).size <=
                MAX_PLAYER_NAME_BYTES,
        ) { "Player name is invalid" }
        val output = ByteArrayOutputStream()
        output.writePacket {
            writeVarInt(CONTROL_JOIN_PACKET_ID)
            writeLong(request.requestId.mostSignificantBits)
            writeLong(request.requestId.leastSignificantBits)
            writeString(playerName)
            writeLong(request.playerUuid.mostSignificantBits)
            writeLong(request.playerUuid.leastSignificantBits)
        }
        return output.toByteArray()
    }

    fun decodeJoinRequest(
        bytes: ByteArray,
    ): FriendControlDecode<FriendJoinRequest> {
        if (bytes.size > MAX_REQUEST_BYTES) return FriendControlDecode.Invalid
        return decode(bytes) {
            val control = readPacket()
            ensure(control.readVarInt() == CONTROL_JOIN_PACKET_ID)
            val request = FriendJoinRequest(
                requestId = UUID(control.readLong(), control.readLong()),
                playerName = control.readString(MAX_PLAYER_NAME_BYTES),
                playerUuid = UUID(control.readLong(), control.readLong()),
            )
            control.ensureFinished()
            request
        }
    }

    private fun encodeIdRequest(packetId: Int, id: UUID): ByteArray {
        val output = ByteArrayOutputStream()
        output.writePacket {
            writeVarInt(packetId)
            writeLong(id.mostSignificantBits)
            writeLong(id.leastSignificantBits)
        }
        return output.toByteArray()
    }

    private fun <A> decodeIdRequest(
        bytes: ByteArray,
        packetId: Int,
        create: (UUID) -> A,
    ): FriendControlDecode<A> {
        if (bytes.size > MAX_REQUEST_BYTES) return FriendControlDecode.Invalid
        return decode(bytes) {
            val control = readPacket()
            ensure(control.readVarInt() == packetId)
            val value = create(UUID(control.readLong(), control.readLong()))
            control.ensureFinished()
            value
        }
    }

    fun isStatusHandshake(bytes: ByteArray): Boolean = try {
        val reader = Reader(bytes)
        val handshake = reader.readPacket()
        handshake.readVarInt() == HANDSHAKE_PACKET_ID &&
            handshake.run {
                readVarInt()
                readString(MAX_ADDRESS_BYTES)
                readUnsignedShort()
                readVarInt() == STATUS_INTENTION
            }
    } catch (_: DecodeFailure) {
        false
    }

    fun inspectControlRequest(
        bytes: ByteArray,
    ): FriendControlDecode<Boolean> = decode(bytes) {
        val firstPacket = readPacket()
        firstPacket.readVarInt() == CONTROL_REQUEST_PACKET_ID
    }

    fun inspectControlMessage(
        bytes: ByteArray,
    ): FriendControlDecode<FriendControlMessageKind> = decode(bytes) {
        val packet = readPacket()
        when (packet.readVarInt()) {
            CONTROL_REQUEST_PACKET_ID -> FriendControlMessageKind.PAIRING
            CONTROL_REMOVAL_PACKET_ID -> FriendControlMessageKind.REMOVAL
            CONTROL_ACTIVITY_PACKET_ID -> FriendControlMessageKind.ACTIVITY
            CONTROL_JOIN_PACKET_ID -> FriendControlMessageKind.JOIN
            else -> FriendControlMessageKind.OTHER
        }
    }

    fun encodeResponse(response: FriendControlResponse): ByteArray {
        val output = ByteArrayOutputStream()
        output.writePacket {
            writeVarInt(CONTROL_RESPONSE_PACKET_ID)
            when (response) {
                FriendControlResponse.Received -> write(0)
                is FriendControlResponse.Accepted -> {
                    write(1)
                    writeString(response.invitation)
                }

                FriendControlResponse.Declined -> write(2)
                FriendControlResponse.TimedOut -> write(3)
                FriendControlResponse.Invalid -> write(4)
                FriendControlResponse.Removed -> write(5)
                is FriendControlResponse.Activity -> {
                    write(6)
                    write(response.activity.kind.ordinal)
                    write(if (response.activity.joinable) 1 else 0)
                    writeString(response.activity.sessionEpoch.orEmpty())
                    writeString(response.activity.description.orEmpty())
                    val compatibility = response.activity.compatibility
                    write(if (compatibility == null) 0 else 1)
                    if (compatibility != null) {
                        writeCompatibilityProfile(compatibility)
                    }
                }
                is FriendControlResponse.JoinAccepted -> {
                    write(7)
                    writeString(response.address)
                }
                FriendControlResponse.SharedWorldJoinAccepted -> write(8)
            }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_REQUEST_BYTES) {
                "Friend response is too large"
            }
        }
    }

    fun decodeResponse(
        bytes: ByteArray,
    ): FriendControlDecode<FriendControlResponse> = decode(bytes) {
        val response = readPacket()
        ensure(response.readVarInt() == CONTROL_RESPONSE_PACKET_ID)
        val decoded = when (response.readByte()) {
            0 -> FriendControlResponse.Received
            1 -> FriendControlResponse.Accepted(
                response.readString(MAX_INVITATION_BYTES),
            )

            2 -> FriendControlResponse.Declined
            3 -> FriendControlResponse.TimedOut
            4 -> FriendControlResponse.Invalid
            5 -> FriendControlResponse.Removed
            6 -> {
                val kind = FriendActivityKind.entries.getOrNull(
                    response.readByte(),
                ) ?: invalid()
                FriendControlResponse.Activity(
                    FriendActivity(
                        kind = kind,
                        joinable = response.readByte() != 0,
                        sessionEpoch = response
                            .readString(MAX_SESSION_EPOCH_BYTES)
                            .takeIf(String::isNotEmpty),
                        description = response.readString(MAX_ACTIVITY_BYTES)
                            .takeIf(String::isNotEmpty),
                        compatibility = when (response.readByte()) {
                            0 -> null
                            1 -> response.readCompatibilityProfile()
                            else -> invalid()
                        },
                    ),
                )
            }
            7 -> FriendControlResponse.JoinAccepted(
                response.readString(MAX_SERVER_ADDRESS_BYTES),
            )
            8 -> FriendControlResponse.SharedWorldJoinAccepted
            else -> invalid()
        }
        response.ensureFinished()
        decoded
    }

    private inline fun <A> decode(
        bytes: ByteArray,
        block: Reader.() -> A,
    ): FriendControlDecode<A> = try {
        val reader = Reader(bytes)
        val value = reader.block()
        FriendControlDecode.Decoded(value, reader.position)
    } catch (_: IncompleteFailure) {
        FriendControlDecode.Incomplete
    } catch (_: InvalidFailure) {
        FriendControlDecode.Invalid
    }

    private fun ByteArrayOutputStream.writePacket(
        payload: ByteArrayOutputStream.() -> Unit,
    ) {
        val packet = ByteArrayOutputStream().apply(payload).toByteArray()
        writeVarInt(packet.size)
        write(packet)
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(encoded.size)
        write(encoded)
    }

    private fun ByteArrayOutputStream.writeCompatibilityProfile(
        profile: CompatibilityProfile,
    ) {
        require(profile.requiredMods.size <= MAX_REQUIRED_MODS) {
            "Compatibility profile has too many required mods"
        }
        writeString(profile.minecraftVersion)
        write(profile.loader.ordinal)
        writeVarInt(profile.requiredMods.size)
        profile.requiredMods.forEach { mod ->
            require(
                mod.id.toByteArray(StandardCharsets.UTF_8).size <=
                    MAX_MOD_ID_BYTES &&
                    mod.version.toByteArray(StandardCharsets.UTF_8).size <=
                    MAX_VERSION_BYTES,
            ) { "Compatibility mod entry is too large" }
            writeString(mod.id)
            writeString(mod.version)
        }
        profile.pack?.let { pack ->
            require(
                listOf(pack.projectId, pack.versionId, pack.url).all {
                    it.toByteArray(StandardCharsets.UTF_8).size <=
                        MAX_PACK_FIELD_BYTES
                },
            ) { "Pack reference is too large" }
            write(1)
            write(pack.platform.ordinal)
            writeString(pack.projectId)
            writeString(pack.versionId)
            writeString(pack.url)
        } ?: write(0)
    }

    private fun ByteArrayOutputStream.writeLong(value: Long) {
        write(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    private fun ByteArrayOutputStream.writeVarInt(value: Int) {
        var remaining = value
        do {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) {
                byte = byte or 0x80
            }
            write(byte)
        } while (remaining != 0)
    }

    private open class DecodeFailure : RuntimeException()

    private class IncompleteFailure : DecodeFailure()

    private class InvalidFailure : DecodeFailure()

    private fun invalid(): Nothing = throw InvalidFailure()

    private class Reader(
        private val bytes: ByteArray,
        private val end: Int = bytes.size,
        var position: Int = 0,
    ) {
        fun readPacket(): Reader {
            val length = readVarInt()
            if (length < 0 || length > MAX_REQUEST_BYTES) {
                invalid()
            }
            val packetEnd = position + length
            if (packetEnd < position || packetEnd > end) {
                throw IncompleteFailure()
            }
            val packet = Reader(bytes, packetEnd, position)
            position = packetEnd
            return packet
        }

        fun readVarInt(): Int {
            var result = 0
            var shift = 0
            while (shift < 35) {
                val byte = readByte()
                result = result or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) {
                    return result
                }
                shift += 7
            }
            invalid()
        }

        fun readUnsignedShort(): Int =
            (readByte() shl 8) or readByte()

        fun readLong(): Long {
            requireAvailable(Long.SIZE_BYTES)
            return ByteBuffer.wrap(
                bytes,
                position,
                Long.SIZE_BYTES,
            ).long.also {
                position += Long.SIZE_BYTES
            }
        }

        fun readString(maxBytes: Int): String {
            val length = readVarInt()
            if (length < 0 || length > maxBytes) {
                invalid()
            }
            requireAvailable(length)
            return String(
                bytes,
                position,
                length,
                StandardCharsets.UTF_8,
            ).also {
                position += length
            }
        }

        fun readByte(): Int {
            requireAvailable(1)
            return bytes[position++].toInt() and 0xff
        }

        fun readCompatibilityProfile(): CompatibilityProfile {
            val minecraftVersion = readString(MAX_VERSION_BYTES)
            ensure(minecraftVersion.isNotBlank())
            val loader = ModLoader.entries.getOrNull(readByte()) ?: invalid()
            val modCount = readVarInt()
            ensure(modCount in 0..MAX_REQUIRED_MODS)
            val mods = buildList {
                repeat(modCount) {
                    val id = readString(MAX_MOD_ID_BYTES)
                    val version = readString(MAX_VERSION_BYTES)
                    ensure(id.isNotBlank() && version.isNotBlank())
                    add(RequiredMod(id, version))
                }
            }
            val pack = when (readByte()) {
                0 -> null
                1 -> PackReference(
                    platform = PackPlatform.entries.getOrNull(readByte())
                        ?: invalid(),
                    projectId = readString(MAX_PACK_FIELD_BYTES),
                    versionId = readString(MAX_PACK_FIELD_BYTES),
                    url = readString(MAX_PACK_FIELD_BYTES),
                )
                else -> invalid()
            }
            return CompatibilityProfile(
                minecraftVersion = minecraftVersion,
                loader = loader,
                requiredMods = mods,
                pack = pack,
            )
        }

        fun ensure(condition: Boolean) {
            if (!condition) {
                invalid()
            }
        }

        fun ensureFinished() {
            ensure(position == end)
        }

        private fun requireAvailable(count: Int) {
            if (count < 0 || position + count < position) {
                invalid()
            }
            if (position + count > end) {
                throw IncompleteFailure()
            }
        }
    }
}
