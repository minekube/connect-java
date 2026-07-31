package com.minekube.connect.share.friend

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

data class FriendControlRequest(
    val requestId: UUID,
    val displayName: String,
    val invitation: String,
)

sealed interface FriendControlResponse {
    data object Received : FriendControlResponse

    data class Accepted(
        val invitation: String,
    ) : FriendControlResponse

    data object Declined : FriendControlResponse

    data object TimedOut : FriendControlResponse

    data object Invalid : FriendControlResponse
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
    const val CONTROL_HANDSHAKE_PORT = 24_454

    private const val STATUS_INTENTION = 1
    private const val HANDSHAKE_PACKET_ID = 0
    private const val STATUS_REQUEST_PACKET_ID = 0
    private const val CONTROL_REQUEST_PACKET_ID = 0x43F1
    private const val CONTROL_RESPONSE_PACKET_ID = 0x43F2
    private const val MAX_ADDRESS_BYTES = 255
    private const val MAX_DISPLAY_NAME_BYTES = 256
    private const val MAX_INVITATION_BYTES = 32_768

    fun encodeRequest(
        protocolVersion: Int,
        serverAddress: String,
        request: FriendControlRequest,
    ): ByteArray {
        require(protocolVersion >= 0) {
            "Minecraft protocol version must not be negative"
        }
        require(serverAddress.toByteArray(StandardCharsets.UTF_8).size <= MAX_ADDRESS_BYTES) {
            "Minecraft server address is too long"
        }
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
            writeVarInt(HANDSHAKE_PACKET_ID)
            writeVarInt(protocolVersion)
            writeString(serverAddress)
            write((CONTROL_HANDSHAKE_PORT ushr 8) and 0xff)
            write(CONTROL_HANDSHAKE_PORT and 0xff)
            writeVarInt(STATUS_INTENTION)
        }
        output.writePacket {
            writeVarInt(STATUS_REQUEST_PACKET_ID)
        }
        output.writePacket {
            writeVarInt(CONTROL_REQUEST_PACKET_ID)
            writeLong(request.requestId.mostSignificantBits)
            writeLong(request.requestId.leastSignificantBits)
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
        return decode(bytes) {
            val handshake = readPacket()
            ensure(handshake.readVarInt() == HANDSHAKE_PACKET_ID)
            handshake.readVarInt()
            handshake.readString(MAX_ADDRESS_BYTES)
            ensure(handshake.readUnsignedShort() == CONTROL_HANDSHAKE_PORT)
            ensure(handshake.readVarInt() == STATUS_INTENTION)
            handshake.ensureFinished()

            val statusRequest = readPacket()
            ensure(
                statusRequest.readVarInt() == STATUS_REQUEST_PACKET_ID,
            )
            statusRequest.ensureFinished()

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
                displayName = displayName,
                invitation = invitation,
            )
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

    fun inspectControlHandshake(
        bytes: ByteArray,
    ): FriendControlDecode<Boolean> = decode(bytes) {
        val handshake = readPacket()
        ensure(handshake.readVarInt() == HANDSHAKE_PACKET_ID)
        handshake.readVarInt()
        handshake.readString(MAX_ADDRESS_BYTES)
        val port = handshake.readUnsignedShort()
        val intention = handshake.readVarInt()
        handshake.ensureFinished()
        port == CONTROL_HANDSHAKE_PORT &&
            intention == STATUS_INTENTION
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
            }
        }
        return output.toByteArray()
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
