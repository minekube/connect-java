package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.friend.FriendControlDecode
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendControlResponse
import com.minekube.connect.share.friend.FriendControlWire
import com.minekube.connect.share.friend.FriendRemovalRequest
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendActivityRequest
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
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
    fun `libp2p control request waits for remote acceptance without joining`() =
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
                ioDispatcher = Dispatchers.IO,
            )

            val result = client.exchange(
                target = directTarget(server),
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
                ioDispatcher = Dispatchers.IO,
                decisionTimeout = Duration.ofSeconds(30),
            )
            val pending = launch {
                client.exchange(
                    target = directTarget(server),
                    request = REQUEST,
                    onReceived = {},
                )
            }
            delay(100)

            pending.cancelAndJoin()

            assertTrue(closed.await(2, TimeUnit.SECONDS))
            remote.join(1_000)
        }

    @Test
    fun `removal waits for a remote acknowledgement`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val removal = FriendRemovalRequest(UUID.randomUUID())
        val remote = thread(name = "friend-removal-test") {
            server.use {
                it.accept().use { socket ->
                    val bytes = socket.getInputStream().readNBytes(
                        FriendControlWire.encodeRemoval(removal).size,
                    )
                    assertEquals(
                        removal,
                        assertIs<FriendControlDecode.Decoded<FriendRemovalRequest>>(
                            FriendControlWire.decodeRemoval(bytes),
                        ).value,
                    )
                    socket.getOutputStream().apply {
                        write(FriendControlWire.encodeResponse(FriendControlResponse.Received))
                        write(FriendControlWire.encodeResponse(FriendControlResponse.Removed))
                        flush()
                    }
                }
            }
        }

        val result = FriendRequestClient(ioDispatcher = Dispatchers.IO)
            .remove(directTarget(server), removal)

        assertIs<Either.Right<Unit>>(result)
        remote.join(1_000)
    }

    @Test
    fun `activity query returns privacy safe friend activity`() = runBlocking {
        val request = FriendActivityRequest(UUID.randomUUID())
        val expected = FriendActivity(FriendActivityKind.PLAYING_SERVER, "Hypixel")
        val server = responseServer(
            FriendControlWire.encodeActivityRequest(request),
            FriendControlResponse.Activity(expected),
        )

        val result = FriendRequestClient(ioDispatcher = Dispatchers.IO)
            .activity(directTarget(server), request)

        assertEquals(expected, assertIs<Either.Right<FriendActivity>>(result).value)
    }

    @Test
    fun `join request returns address only after remote acceptance`() = runBlocking {
        val request = FriendJoinRequest(UUID.randomUUID())
        val server = responseServer(
            FriendControlWire.encodeJoinRequest(request),
            FriendControlResponse.JoinAccepted("mc.hypixel.net"),
        )

        val result = FriendRequestClient(ioDispatcher = Dispatchers.IO)
            .requestJoin(directTarget(server), request)

        assertEquals("mc.hypixel.net", assertIs<Either.Right<String>>(result).value)
    }

    private fun responseServer(
        expectedRequest: ByteArray,
        response: FriendControlResponse,
    ): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(name = "friend-control-response-test") {
            server.use {
                it.accept().use { socket ->
                    assertTrue(
                        expectedRequest.contentEquals(
                            socket.getInputStream().readNBytes(expectedRequest.size),
                        ),
                    )
                    socket.getOutputStream().apply {
                        write(FriendControlWire.encodeResponse(FriendControlResponse.Received))
                        write(FriendControlWire.encodeResponse(response))
                        flush()
                    }
                }
            }
        }
        return server
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

    private fun directTarget(server: ServerSocket): GuestJoinTarget.Direct {
        val address = InetSocketAddress(
            InetAddress.getLoopbackAddress(),
            server.localPort,
        )
        return GuestJoinTarget.Direct(
            ShareRoute.DIRECT_LAN,
            address,
            DirectP2pProxy(address) {},
        )
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
