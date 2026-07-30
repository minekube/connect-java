package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ServerPresence(
    val description: String,
)

sealed interface StatusProbeError {
    data object InvalidAddress : StatusProbeError

    data object Unreachable : StatusProbeError

    data object InvalidResponse : StatusProbeError

    data object EndpointOffline : StatusProbeError
}

fun interface FriendStatusProbe {
    suspend fun probe(
        address: String,
    ): Either<StatusProbeError, ServerPresence>
}

class MinecraftStatusProbe(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FriendStatusProbe {
    override suspend fun probe(
        address: String,
    ): Either<StatusProbeError, ServerPresence> =
        withContext(ioDispatcher) {
            either {
                val target = parseAddress(address).bind()
                val json = Either.catch {
                    requestStatus(target)
                }.mapLeft {
                    StatusProbeError.Unreachable
                }.bind()
                val description = Either.catch {
                    flattenDescription(
                        JsonParser.parseString(json)
                            .asJsonObject
                            .get("description"),
                    )
                }.mapLeft {
                    StatusProbeError.InvalidResponse
                }.bind()
                ensure(!description.isOfflineFallback()) {
                    StatusProbeError.EndpointOffline
                }
                ServerPresence(description)
            }
        }

    private fun requestStatus(target: InetSocketAddress): String {
        Socket().use { socket ->
            socket.soTimeout = TIMEOUT_MILLIS
            socket.connect(target, TIMEOUT_MILLIS)
            val output = socket.getOutputStream()
            val handshake = ByteArrayOutputStream().apply {
                writeVarInt(0)
                writeVarInt(0)
                writeString(target.hostString)
                write(target.port ushr 8)
                write(target.port and 0xff)
                writeVarInt(1)
            }.toByteArray()
            output.writePacket(handshake)
            output.writePacket(byteArrayOf(0))
            output.flush()

            val input = DataInputStream(socket.getInputStream())
            val packetLength = input.readVarInt()
            require(packetLength in 1..MAX_PACKET_BYTES)
            val packet = DataInputStream(
                ByteArrayInputStream(input.readNBytes(packetLength)),
            )
            require(packet.readVarInt() == 0)
            val jsonLength = packet.readVarInt()
            require(jsonLength in 1..MAX_PACKET_BYTES)
            return String(
                packet.readNBytes(jsonLength),
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun parseAddress(
        value: String,
    ): Either<StatusProbeError.InvalidAddress, InetSocketAddress> =
        Either.catch {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty())
            val host: String
            val port: Int
            if (trimmed.startsWith("[")) {
                val closing = trimmed.indexOf(']')
                require(closing > 1)
                host = trimmed.substring(1, closing)
                port = if (closing + 1 < trimmed.length) {
                    require(trimmed[closing + 1] == ':')
                    trimmed.substring(closing + 2).toInt()
                } else {
                    DEFAULT_PORT
                }
            } else if (trimmed.count { it == ':' } == 1) {
                host = trimmed.substringBeforeLast(':')
                port = trimmed.substringAfterLast(':').toInt()
            } else {
                host = trimmed
                port = DEFAULT_PORT
            }
            require(host.isNotBlank() && port in 1..65_535)
            InetSocketAddress(host, port)
        }.mapLeft {
            StatusProbeError.InvalidAddress
        }

    private fun flattenDescription(element: JsonElement?): String = when {
        element == null || element.isJsonNull -> ""
        element.isJsonPrimitive -> element.asString
        element.isJsonArray -> element.asJsonArray.joinToString("") {
            flattenDescription(it)
        }
        else -> {
            val json = element.asJsonObject
            buildString {
                json.get("text")?.let {
                    append(flattenDescription(it))
                }
                json.get("translate")?.let {
                    append(flattenDescription(it))
                }
                json.get("extra")?.let {
                    append(flattenDescription(it))
                }
            }
        }
    }

    private fun String.isOfflineFallback(): Boolean {
        val normalized = lowercase()
        return OFFLINE_MARKERS.any(normalized::contains)
    }

    private fun java.io.OutputStream.writePacket(payload: ByteArray) {
        writeVarInt(payload.size)
        write(payload)
    }

    private fun java.io.OutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(bytes.size)
        write(bytes)
    }

    private fun java.io.OutputStream.writeVarInt(value: Int) {
        var remaining = value
        while (true) {
            if (remaining and -128 == 0) {
                write(remaining)
                return
            }
            write(remaining and 127 or 128)
            remaining = remaining ushr 7
        }
    }

    private fun DataInputStream.readVarInt(): Int {
        var value = 0
        var position = 0
        while (position < 32) {
            val current = readUnsignedByte()
            value = value or ((current and 0x7f) shl position)
            if (current and 0x80 == 0) {
                return value
            }
            position += 7
        }
        throw IllegalArgumentException("VarInt is too large")
    }

    private companion object {
        const val DEFAULT_PORT = 25_565
        const val TIMEOUT_MILLIS = 2_500
        const val MAX_PACKET_BYTES = 1024 * 1024
        val OFFLINE_MARKERS = listOf(
            " is currently not available.",
            " could not be pinged",
        )
    }
}
