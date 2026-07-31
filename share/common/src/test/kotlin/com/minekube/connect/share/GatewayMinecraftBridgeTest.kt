package com.minekube.connect.share

import com.minekube.connect.share.friend.FriendControlResponse
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.local.LocalAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GatewayMinecraftBridgeTest {
    @Test
    fun `world bridge activates stable gateway targets only for world lifetime`() =
        runBlocking {
            val transport = FakeTransport()
            ShareConnectionGateway.bind { _, _ ->
                CompletableFuture.completedFuture(
                    FriendControlResponse.Invalid,
                )
            }.use { gateway ->
                val bridge = VersionedMinecraftBridge(
                    transport = transport,
                    gateway = gateway,
                )

                val target = bridge.open(
                    ShareOptions(
                        gameMode = ShareGameMode.SURVIVAL,
                        allowCheats = false,
                    ),
                )

                assertIs<LocalAddress>(target.address)
                assertEquals(
                    gateway.serverSocketAddress,
                    target.address,
                )
                assertEquals(gateway.directAddress, target.directAddress)
                assertContentEquals(
                    MINECRAFT_BYTES,
                    exchange(gateway.directAddress, MINECRAFT_BYTES),
                )
                assertEquals(0, transport.localListenersAdded)

                target.close()

                assertTrue(
                    exchangeClosed(
                        gateway.directAddress,
                        MINECRAFT_BYTES,
                    ),
                )
                assertTrue(transport.published.closed)
                assertEquals(0, transport.localListenersRemoved)
            }
        }

    private fun exchange(
        address: InetSocketAddress,
        bytes: ByteArray,
    ): ByteArray = Socket().use { socket ->
        socket.soTimeout = 2_000
        socket.connect(address)
        socket.getOutputStream().apply {
            write(bytes)
            flush()
        }
        socket.getInputStream().readNBytes(bytes.size)
    }

    private fun exchangeClosed(
        address: InetSocketAddress,
        bytes: ByteArray,
    ): Boolean = Socket().use { socket ->
        socket.soTimeout = 2_000
        socket.connect(address)
        socket.getOutputStream().apply {
            write(bytes)
            flush()
        }
        socket.getInputStream().read() == -1
    }

    private class FakeTransport : MinecraftVersionTransport {
        val published = FakePublishedTransport()
        var localListenersAdded = 0
        var localListenersRemoved = 0

        override fun publish(
            options: ShareOptions,
        ): PublishedMinecraftTransport = published.also {
            it.onAdd = { localListenersAdded++ }
            it.onRemove = { localListenersRemoved++ }
        }
    }

    private class FakePublishedTransport : PublishedMinecraftTransport {
        override val address = InetSocketAddress(
            InetAddress.getLoopbackAddress(),
            24_455,
        )
        override val childInitializer =
            object : ChannelInitializer<Channel>() {
                override fun initChannel(channel: Channel) {
                    channel.pipeline().addLast(
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(
                                context: ChannelHandlerContext,
                                message: Any,
                            ) {
                                val buffer = message as ByteBuf
                                val bytes = ByteArray(buffer.readableBytes())
                                buffer.readBytes(bytes)
                                buffer.release()
                                context.writeAndFlush(
                                    Unpooled.wrappedBuffer(bytes),
                                )
                            }
                        },
                    )
                }
            }
        var onAdd: () -> Unit = {}
        var onRemove: () -> Unit = {}
        var closed = false

        override fun addLocalListener(listener: LocalShareChannel) {
            onAdd()
        }

        override fun removeLocalListener(listener: LocalShareChannel) {
            onRemove()
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val MINECRAFT_BYTES = byteArrayOf(
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
