package com.minekube.connect.share.friend

import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.DirectSessionAttributes
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pRoute
import com.minekube.connect.tunnel.p2p.DirectP2pSession
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendControlChannelHandlerTest {
    @Test
    fun `ordinary Minecraft traffic passes through unchanged`() {
        val ordinary = ORDINARY_MINECRAFT_HANDSHAKE
        val channel = EmbeddedChannel(
            FriendControlChannelHandler { _, _ ->
                error("Ordinary traffic must not reach friend control")
            },
        )

        assertTrue(
            channel.writeInbound(
                Unpooled.wrappedBuffer(ordinary),
            ),
        )
        val forwarded = channel.readInbound<ByteBuf>()
        val actual = ByteArray(forwarded.readableBytes())
        forwarded.readBytes(actual)
        forwarded.release()

        assertTrue(ordinary.contentEquals(actual))
        channel.finishAndReleaseAll()
    }

    @Test
    fun `fragmented request is intercepted and responses stream without vanilla`() {
        val response = CompletableFuture<FriendControlResponse>()
        val received = mutableListOf<Pair<FriendControlContext, FriendControlRequest>>()
        val channel = EmbeddedChannel(
            FriendControlChannelHandler { context, request ->
                received += context to request
                response
            },
        )
        channel.attr(DirectSessionAttributes.SESSION).set(DIRECT_SESSION)
        val encoded = FriendControlWire.encodeRequest(
            request = REQUEST,
        )

        channel.writeInbound(
            Unpooled.wrappedBuffer(encoded.copyOfRange(0, 7)),
        )
        assertTrue(received.isEmpty())
        channel.writeInbound(
            Unpooled.wrappedBuffer(encoded.copyOfRange(7, encoded.size)),
        )

        assertEquals(REQUEST, received.single().second)
        assertEquals(Ingress.DIRECT_LAN, received.single().first.ingress)
        assertEquals(
            DIRECT_SESSION.peerId(),
            received.single().first.directPeerId,
        )
        assertNull(channel.readInbound<ByteBuf>())
        assertEquals(
            FriendControlResponse.Received,
            channel.readControlResponse(),
        )

        response.complete(
            FriendControlResponse.Accepted(
                "minekube://share/host-card",
            ),
        )
        channel.runPendingTasks()

        assertEquals(
            FriendControlResponse.Accepted(
                "minekube://share/host-card",
            ),
            channel.readControlResponse(),
        )
        assertTrue(!channel.isOpen)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `closing sender cancels remote pending decision`() {
        val response = CompletableFuture<FriendControlResponse>()
        val channel = EmbeddedChannel(
            FriendControlChannelHandler { _, _ -> response },
        )
        channel.writeInbound(
            Unpooled.wrappedBuffer(
                FriendControlWire.encodeRequest(
                    request = REQUEST,
                ),
            ),
        )
        channel.readOutbound<ByteBuf>()?.release()

        channel.close()

        assertTrue(response.isCancelled)
        channel.finishAndReleaseAll()
    }

    @Test
    fun `removal command is dispatched on the authenticated direct session`() {
        val removal = FriendRemovalRequest(UUID.randomUUID())
        var received: Pair<FriendControlContext, FriendRemovalRequest>? = null
        val server = object : FriendControlServer {
            override fun handle(
                context: FriendControlContext,
                request: FriendControlRequest,
            ): java.util.concurrent.CompletionStage<FriendControlResponse> =
                CompletableFuture.completedFuture(
                    FriendControlResponse.Invalid,
                )

            override fun handleRemoval(
                context: FriendControlContext,
                request: FriendRemovalRequest,
            ): CompletableFuture<FriendControlResponse> {
                received = context to request
                return CompletableFuture.completedFuture(
                    FriendControlResponse.Removed,
                )
            }
        }
        val channel = EmbeddedChannel(FriendControlChannelHandler(server))
        channel.attr(DirectSessionAttributes.SESSION).set(DIRECT_SESSION)

        channel.writeInbound(
            Unpooled.wrappedBuffer(FriendControlWire.encodeRemoval(removal)),
        )
        channel.runPendingTasks()

        assertEquals(removal, received?.second)
        assertEquals(DIRECT_SESSION.peerId(), received?.first?.directPeerId)
        assertEquals(FriendControlResponse.Received, channel.readControlResponse())
        assertEquals(FriendControlResponse.Removed, channel.readControlResponse())
        channel.finishAndReleaseAll()
    }

    private fun EmbeddedChannel.readControlResponse(): FriendControlResponse {
        val buffer = readOutbound<ByteBuf>()
        val bytes = ByteArray(buffer.readableBytes())
        buffer.readBytes(bytes)
        buffer.release()
        return assertIs<
            FriendControlDecode.Decoded<FriendControlResponse>
            >(FriendControlWire.decodeResponse(bytes)).value
    }

    private companion object {
        val REQUEST = FriendControlRequest(
            requestId = UUID.fromString(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            ),
            displayName = "bob",
            invitation = "minekube://share/sender-card",
        )
        val DIRECT_SESSION = DirectP2pSession(
            "12D3KooWSender",
            DirectP2pAuthMode.OFFLINE,
            DirectP2pRoute.LAN,
            "direct-control-session",
        )
        val ORDINARY_MINECRAFT_HANDSHAKE = byteArrayOf(
            0x10,
            0x00,
            0xb3.toByte(),
            0x08,
            0x09,
            *"localhost".toByteArray(),
            0x63,
            0xdd.toByte(),
            0x02,
        )
    }
}
