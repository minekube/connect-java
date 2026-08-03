package com.minekube.connect.share.fabric

import arrow.core.Either
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class MinecraftStatusProbeTest {
    @Test
    fun `status response from a live endpoint is online`() = runTest {
        fakeStatusServer(
            """{"version":{"name":"test","protocol":1},"players":{"max":8,"online":1},"description":{"text":"Robin's World"}}""",
        ).use { server ->
            val result = MinecraftStatusProbe().probe(server.address)

            val presence = assertIs<Either.Right<ServerPresence>>(result).value
            assertEquals("Robin's World", presence.description)
        }
    }

    @Test
    fun `Connect fallback MOTD is recognized as offline`() = runTest {
        fakeStatusServer(
            """{"version":{"name":"test","protocol":1},"players":{"max":0,"online":0},"description":{"extra":[{"text":"purple-del"},{"text":" is currently not available."}]}}""",
        ).use { server ->
            val result = MinecraftStatusProbe().probe(server.address)

            assertIs<Either.Left<StatusProbeError.EndpointOffline>>(result)
        }
    }

    private fun fakeStatusServer(json: String): FakeStatusServer {
        val listener = ServerSocket(
            0,
            1,
            InetAddress.getLoopbackAddress(),
        )
        val completed = CompletableFuture<Unit>()
        val thread = Thread {
            try {
                listener.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    input.readNBytes(readVarInt(input))
                    input.readNBytes(readVarInt(input))
                    val response = ByteArrayOutputStream().also { packet ->
                        writeVarInt(packet, 0)
                        val jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
                        writeVarInt(packet, jsonBytes.size)
                        packet.write(jsonBytes)
                    }.toByteArray()
                    val output = socket.getOutputStream()
                    writeVarInt(output, response.size)
                    output.write(response)
                    output.flush()
                }
                completed.complete(Unit)
            } catch (failure: Throwable) {
                completed.completeExceptionally(failure)
            }
        }
        thread.isDaemon = true
        thread.start()
        return FakeStatusServer(
            address = "127.0.0.1:${listener.localPort}",
            close = {
                listener.close()
                completed.get(3, TimeUnit.SECONDS)
            },
        )
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var position = 0
        while (position < 32) {
            val current = input.readUnsignedByte()
            value = value or ((current and 0x7f) shl position)
            if (current and 0x80 == 0) return value
            position += 7
        }
        error("VarInt is too large")
    }

    private fun writeVarInt(
        output: java.io.OutputStream,
        value: Int,
    ) {
        var remaining = value
        while (true) {
            if (remaining and -128 == 0) {
                output.write(remaining)
                return
            }
            output.write(remaining and 127 or 128)
            remaining = remaining ushr 7
        }
    }

    private class FakeStatusServer(
        val address: String,
        private val close: () -> Unit,
    ) : AutoCloseable {
        override fun close() = close.invoke()
    }
}
