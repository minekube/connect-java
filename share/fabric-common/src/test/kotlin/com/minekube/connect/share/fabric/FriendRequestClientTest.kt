package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.friend.FriendControlDecode
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendControlResponse
import com.minekube.connect.share.friend.FriendControlWire
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class FriendRequestClientTest {
    @Test
    fun `Connect control request waits for remote acceptance without joining`() =
        runBlocking {
            val server = ServerSocket(
                0,
                1,
                InetAddress.getLoopbackAddress(),
            )
            val received = CountDownLatch(1)
            val remote = thread(name = "friend-control-test") {
                server.use {
                    it.accept().use { socket ->
                        val request = socket.getInputStream()
                            .readControlRequest()
                        assertEquals(REQUEST, request)
                        socket.getOutputStream().apply {
                            write(
                                FriendControlWire.encodeResponse(
                                    FriendControlResponse.Received,
                                ),
                            )
                            flush()
                        }
                        received.countDown()
                        socket.getOutputStream().apply {
                            write(
                                FriendControlWire.encodeResponse(
                                    FriendControlResponse.Accepted(HOST_CARD),
                                ),
                            )
                            flush()
                        }
                    }
                }
            }
            var acknowledged = false
            val client = FriendRequestClient(
                protocolVersion = 1_075,
                ioDispatcher = Dispatchers.IO,
            )

            val result = client.exchange(
                target = GuestJoinTarget.Connect(
                    "${InetAddress.getLoopbackAddress().hostAddress}:${server.localPort}",
                ),
                request = REQUEST,
                onReceived = { acknowledged = true },
            )

            assertIs<Either.Right<String>>(result)
            assertEquals(HOST_CARD, result.value)
            assertTrue(acknowledged)
            assertTrue(received.await(1, TimeUnit.SECONDS))
            remote.join(1_000)
        }

    @Test
    fun `cancelling a pending request closes its control socket promptly`() =
        runBlocking {
            val server = ServerSocket(
                0,
                1,
                InetAddress.getLoopbackAddress(),
            )
            val closed = CountDownLatch(1)
            val remote = thread(name = "friend-control-cancel-test") {
                server.use {
                    it.accept().use { socket ->
                        socket.getInputStream().readControlRequest()
                        socket.getOutputStream().apply {
                            write(
                                FriendControlWire.encodeResponse(
                                    FriendControlResponse.Received,
                                ),
                            )
                            flush()
                        }
                        while (socket.getInputStream().read() != -1) {
                            // Wait for cancellation to close the stream.
                        }
                        closed.countDown()
                    }
                }
            }
            val client = FriendRequestClient(
                protocolVersion = 1_075,
                ioDispatcher = Dispatchers.IO,
                decisionTimeout = Duration.ofSeconds(30),
            )
            val pending = launch {
                client.exchange(
                    target = GuestJoinTarget.Connect(
                        "${InetAddress.getLoopbackAddress().hostAddress}:${server.localPort}",
                    ),
                    request = REQUEST,
                    onReceived = {},
                )
            }
            delay(100)

            pending.cancelAndJoin()

            assertTrue(closed.await(2, TimeUnit.SECONDS))
            remote.join(1_000)
        }

    private fun java.io.InputStream.readControlRequest(): FriendControlRequest {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= FriendControlWire.MAX_REQUEST_BYTES) {
            val next = read()
            check(next >= 0) { "Friend control request ended early" }
            bytes.write(next)
            when (
                val decoded =
                    FriendControlWire.decodeRequest(bytes.toByteArray())
            ) {
                is FriendControlDecode.Decoded -> return decoded.value
                FriendControlDecode.Incomplete -> Unit
                FriendControlDecode.Invalid ->
                    error("Friend control request was invalid")
            }
        }
        error("Friend control request exceeded its limit")
    }

    private companion object {
        val REQUEST = FriendControlRequest(
            requestId = UUID.fromString(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            ),
            displayName = "bob",
            invitation = "minekube://share/sender-card",
        )
        const val HOST_CARD = "minekube://share/host-card"
    }
}
