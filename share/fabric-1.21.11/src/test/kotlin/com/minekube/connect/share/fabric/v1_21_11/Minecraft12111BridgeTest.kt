package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.EventLoopGroup
import io.netty.channel.local.LocalAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class Minecraft12111BridgeTest {
    @Test
    fun `publishes only on loopback and releases every listener`() = runBlocking {
        val transport = FakeMinecraftTransport()
        val bridge = Minecraft12111Bridge(transport, FakeLocalChannelBinder())

        val target = bridge.open(shareOptions)

        assertTrue(transport.boundAddress.address.isLoopbackAddress)
        assertIs<LocalAddress>(target.address)
        assertEquals(2, transport.listenerCount)
        assertEquals(25565, transport.publishedPort)

        target.close()

        assertEquals(-1, transport.publishedPort)
        assertEquals(0, transport.listenerCount)
    }

    @Test
    fun `can open again after close but never installs a second active listener`() = runBlocking {
        val transport = FakeMinecraftTransport()
        val bridge = Minecraft12111Bridge(transport, FakeLocalChannelBinder())

        val first = bridge.open(shareOptions)
        assertFailsWith<IllegalStateException> {
            bridge.open(shareOptions)
        }
        assertEquals(2, transport.listenerCount)

        first.close()
        val second = bridge.open(shareOptions)

        assertEquals(2, transport.listenerCount)
        second.close()
        assertEquals(0, transport.listenerCount)
    }

    @Test
    fun `failed local bind rolls back the private vanilla listener`() = runBlocking {
        val transport = FakeMinecraftTransport()
        val bridge = Minecraft12111Bridge(
            transport,
            LocalShareChannelBinder { _, _ ->
                throw IllegalStateException("local bind failed")
            },
        )

        assertFailsWith<IllegalStateException> {
            bridge.open(shareOptions)
        }

        assertEquals(-1, transport.publishedPort)
        assertEquals(0, transport.listenerCount)
    }

    private class FakeMinecraftTransport : Minecraft12111Transport {
        var publishedPort = -1
        var listenerCount = 0
        lateinit var boundAddress: InetSocketAddress
        val eventLoopGroup: EventLoopGroup = DefaultEventLoopGroup(1)

        override fun publish(options: ShareOptions): PublishedMinecraftTransport {
            check(publishedPort == -1)
            boundAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 25565)
            publishedPort = boundAddress.port
            listenerCount++
            return object : PublishedMinecraftTransport {
                override val address: InetSocketAddress = boundAddress
                override val childInitializer: ChannelInitializer<Channel> = NoopInitializer
                override val eventLoopGroup = this@FakeMinecraftTransport.eventLoopGroup

                override fun addLocalListener(listener: LocalShareChannel) {
                    listenerCount++
                }

                override fun removeLocalListener(listener: LocalShareChannel) {
                    listenerCount--
                }

                override fun close() {
                    if (publishedPort != -1) {
                        listenerCount--
                        publishedPort = -1
                    }
                }
            }
        }
    }

    private class FakeLocalChannelBinder : LocalShareChannelBinder {
        override fun bind(
            childInitializer: ChannelInitializer<Channel>,
            eventLoopGroup: EventLoopGroup,
        ): LocalShareChannel = object : LocalShareChannel {
            override val address: SocketAddress = LocalAddress("connect-share-test")
            override fun close() = Unit
        }
    }

    private object NoopInitializer : ChannelInitializer<Channel>() {
        override fun initChannel(channel: Channel) = Unit
    }

    private companion object {
        val shareOptions = ShareOptions(
            gameMode = ShareGameMode.SURVIVAL,
            allowCheats = false,
        )
    }
}
