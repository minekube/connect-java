package com.minekube.connect.share

import com.minekube.connect.network.netty.LocalChannelWithSessionContext
import com.minekube.connect.share.friend.FriendControlDecode
import com.minekube.connect.share.friend.FriendControlContext
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendControlResponse
import com.minekube.connect.share.friend.FriendControlServer
import com.minekube.connect.share.friend.FriendControlWire
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.local.LocalAddress
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShareConnectionGatewayTest {
    @Test
    fun `host privacy rejects Minecraft status without blocking login`() {
        val server = object : FriendControlServer {
            override fun handle(
                context: FriendControlContext,
                request: FriendControlRequest,
            ): CompletionStage<FriendControlResponse> =
                CompletableFuture.completedFuture(
                    FriendControlResponse.Invalid,
                )

            override fun allowsMinecraftStatus(
                context: FriendControlContext,
            ) = false
        }
        ShareConnectionGateway.bind(server).use { gateway ->
            val received = mutableListOf<ByteArray>()
            gateway.activateMinecraft(
                object : ChannelInitializer<Channel>() {
                    override fun initChannel(channel: Channel) {
                        channel.pipeline().addLast(
                            object : ChannelInboundHandlerAdapter() {
                                override fun channelRead(
                                    context: ChannelHandlerContext,
                                    message: Any,
                                ) {
                                    val buffer = message as ByteBuf
                                    received += ByteArray(buffer.readableBytes())
                                        .also(buffer::readBytes)
                                    buffer.release()
                                    context.close()
                                }
                            },
                        )
                    }
                },
            ).use {
                Socket().use { socket ->
                    socket.soTimeout = 2_000
                    socket.connect(gateway.directAddress)
                    socket.getOutputStream().apply {
                        write(MINECRAFT_STATUS_HANDSHAKE, 0, 3)
                        flush()
                        write(
                            MINECRAFT_STATUS_HANDSHAKE,
                            3,
                            MINECRAFT_STATUS_HANDSHAKE.size - 3,
                        )
                        flush()
                    }
                    assertEquals(-1, socket.getInputStream().read())
                }
                assertTrue(received.isEmpty())

                Socket().use { socket ->
                    socket.soTimeout = 2_000
                    socket.connect(gateway.directAddress)
                    socket.getOutputStream().apply {
                        write(MINECRAFT_LOGIN_HANDSHAKE)
                        flush()
                    }
                    assertEquals(-1, socket.getInputStream().read())
                }
                assertContentEquals(
                    MINECRAFT_LOGIN_HANDSHAKE,
                    received.single(),
                )
            }
        }
    }

    @Test
    fun `friend control is reachable before a Minecraft world exists`() {
        val requests = mutableListOf<FriendControlRequest>()
        ShareConnectionGateway.bind { _, request ->
            requests += request
            CompletableFuture.completedFuture(
                FriendControlResponse.Accepted(HOST_CARD),
            )
        }.use { gateway ->
            Socket().use { socket ->
                socket.connect(gateway.directAddress)
                socket.getOutputStream().apply {
                    write(
                        FriendControlWire.encodeRequest(
                            request = REQUEST,
                        ),
                    )
                    flush()
                }

                assertEquals(
                    FriendControlResponse.Received,
                    socket.getInputStream().readControlResponse(),
                )
                assertEquals(
                    FriendControlResponse.Accepted(HOST_CARD),
                    socket.getInputStream().readControlResponse(),
                )
            }
        }

        assertEquals(listOf(REQUEST), requests)
    }

    @Test
    fun `ordinary Minecraft bytes are rejected until a world is active`() {
        ShareConnectionGateway.bind { _, _ ->
            CompletableFuture.completedFuture(FriendControlResponse.Invalid)
        }.use { gateway ->
            Socket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(gateway.directAddress)
                socket.getOutputStream().apply {
                    write(ORDINARY_MINECRAFT_BYTES)
                    flush()
                }

                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }

    @Test
    fun `ordinary Minecraft bytes route through only the active world`() {
        ShareConnectionGateway.bind { _, _ ->
            CompletableFuture.completedFuture(FriendControlResponse.Invalid)
        }.use { gateway ->
            val received = CompletableFuture<ByteArray>()
            val activated = CompletableFuture<Unit>()
            val world = gateway.activateMinecraft(
                object : ChannelInitializer<Channel>() {
                    override fun initChannel(channel: Channel) {
                        channel.pipeline().addLast(
                            object : ChannelInboundHandlerAdapter() {
                                override fun channelActive(
                                    context: ChannelHandlerContext,
                                ) {
                                    activated.complete(Unit)
                                    context.fireChannelActive()
                                }

                                override fun channelRead(
                                    context: ChannelHandlerContext,
                                    message: Any,
                                ) {
                                    val buffer = message as ByteBuf
                                    val bytes = ByteArray(buffer.readableBytes())
                                    buffer.readBytes(bytes)
                                    buffer.release()
                                    received.complete(bytes)
                                    context.writeAndFlush(
                                        Unpooled.wrappedBuffer(bytes),
                                    )
                                }
                            },
                        )
                    }
                },
            )
            world.use {
                Socket().use { socket ->
                    socket.soTimeout = 2_000
                    socket.connect(gateway.directAddress)
                    socket.getOutputStream().apply {
                        write(ORDINARY_MINECRAFT_BYTES)
                        flush()
                    }

                    assertContentEquals(
                        ORDINARY_MINECRAFT_BYTES,
                        socket.getInputStream().readNBytes(
                            ORDINARY_MINECRAFT_BYTES.size,
                        ),
                    )
                }
                assertContentEquals(
                    ORDINARY_MINECRAFT_BYTES,
                    received.get(2, TimeUnit.SECONDS),
                )
                assertEquals(Unit, activated.get(2, TimeUnit.SECONDS))
            }

            Socket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(gateway.directAddress)
                socket.getOutputStream().apply {
                    write(ORDINARY_MINECRAFT_BYTES)
                    flush()
                }
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }

    @Test
    fun `Connect local channel reaches the same always-on control handler`() {
        ShareConnectionGateway.bind { _, _ ->
            CompletableFuture.completedFuture(
                FriendControlResponse.Accepted(HOST_CARD),
            )
        }.use { gateway ->
            assertIs<LocalAddress>(gateway.serverSocketAddress)
            val eventLoop = DefaultEventLoopGroup(1)
            try {
                val responses = CompletableFuture<List<FriendControlResponse>>()
                val channel = Bootstrap()
                    .channel(LocalChannelWithSessionContext::class.java)
                    .group(eventLoop)
                    .handler(
                        object :
                            ChannelInitializer<LocalChannelWithSessionContext>() {
                            override fun initChannel(
                                channel: LocalChannelWithSessionContext,
                            ) {
                                channel.pipeline().addLast(
                                    object :
                                        SimpleChannelInboundHandler<ByteBuf>() {
                                        private val bytes =
                                            ByteArrayOutputStream()

                                        override fun channelRead0(
                                            context: ChannelHandlerContext,
                                            message: ByteBuf,
                                        ) {
                                            val part = ByteArray(
                                                message.readableBytes(),
                                            )
                                            message.readBytes(part)
                                            bytes.write(part)
                                            val decoded = decodeResponses(
                                                bytes.toByteArray(),
                                            )
                                            if (decoded.size == 2) {
                                                responses.complete(decoded)
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                    .remoteAddress(gateway.serverSocketAddress)
                    .connect()
                    .syncUninterruptibly()
                    .channel()
                try {
                    channel.writeAndFlush(
                        Unpooled.wrappedBuffer(
                            FriendControlWire.encodeRequest(
                                request = REQUEST,
                            ),
                        ),
                    ).syncUninterruptibly()
                    assertEquals(
                        listOf(
                            FriendControlResponse.Received,
                            FriendControlResponse.Accepted(HOST_CARD),
                        ),
                        responses.get(2, TimeUnit.SECONDS),
                    )
                } finally {
                    channel.close().syncUninterruptibly()
                }
            } finally {
                eventLoop.shutdownGracefully().syncUninterruptibly()
            }
        }
    }

    @Test
    fun `closing gateway releases both listeners`() {
        val gateway = ShareConnectionGateway.bind { _, _ ->
            CompletableFuture.completedFuture(FriendControlResponse.Invalid)
        }
        val direct = gateway.directAddress
        val local = gateway.serverSocketAddress

        gateway.close()

        assertTrue(gateway.isClosed)
        assertTrue(
            runCatching {
                Socket().use { it.connect(direct, 250) }
            }.isFailure,
        )
        assertIs<LocalAddress>(local)
    }

    private fun java.io.InputStream.readControlResponse():
        FriendControlResponse {
        val frame = ByteArrayOutputStream()
        var length = 0
        var shift = 0
        while (shift < 35) {
            val byte = read()
            check(byte >= 0)
            frame.write(byte)
            length = length or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) {
                break
            }
            shift += 7
        }
        repeat(length) {
            frame.write(read().also { check(it >= 0) })
        }
        return assertIs<FriendControlDecode.Decoded<FriendControlResponse>>(
            FriendControlWire.decodeResponse(frame.toByteArray()),
        ).value
    }

    private fun decodeResponses(bytes: ByteArray): List<FriendControlResponse> {
        val decoded = mutableListOf<FriendControlResponse>()
        var offset = 0
        while (offset < bytes.size) {
            val next = FriendControlWire.decodeResponse(
                bytes.copyOfRange(offset, bytes.size),
            )
            when (next) {
                is FriendControlDecode.Decoded -> {
                    decoded += next.value
                    offset += next.consumedBytes
                }

                FriendControlDecode.Incomplete -> return decoded
                FriendControlDecode.Invalid ->
                    error("invalid friend control response")
            }
        }
        return decoded
    }

    private companion object {
        val MINECRAFT_STATUS_HANDSHAKE =
            byteArrayOf(0x10, 0x00, 0xff.toByte(), 0x05, 0x09) +
                "localhost".encodeToByteArray() +
                byteArrayOf(0x63, 0xdd.toByte(), 0x01)
        val MINECRAFT_LOGIN_HANDSHAKE =
            MINECRAFT_STATUS_HANDSHAKE.copyOf().also {
                it[it.lastIndex] = 0x02
            }
        val REQUEST = FriendControlRequest(
            requestId = UUID.fromString(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            ),
            displayName = "bob",
            invitation = "minekube://share/sender-card",
        )
        const val HOST_CARD = "minekube://share/host-card"
        val ORDINARY_MINECRAFT_BYTES = byteArrayOf(
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
