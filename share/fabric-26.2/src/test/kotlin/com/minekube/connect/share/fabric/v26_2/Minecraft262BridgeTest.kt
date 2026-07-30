package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
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

class Minecraft262BridgeTest {
    @Test
    fun `matches the cross-version private bridge contract`() = runBlocking {
        val transport = FakeMinecraftTransport()
        val bridge = Minecraft262Bridge(transport, FakeLocalChannelBinder())

        val first = bridge.open(options)
        assertTrue(transport.boundAddress.address.isLoopbackAddress)
        assertIs<LocalAddress>(first.address)
        assertEquals(2, transport.listenerCount)
        assertFailsWith<IllegalStateException> {
            bridge.open(options)
        }
        first.close()

        val second = bridge.open(options)
        assertTrue(transport.boundAddress.address.isLoopbackAddress)
        second.close()

        assertEquals(2, transport.publishCount)
        assertEquals(-1, transport.publishedPort)
        assertEquals(0, transport.listenerCount)
    }

    private class FakeMinecraftTransport : Minecraft262Transport {
        var publishedPort = -1
        var listenerCount = 0
        var publishCount = 0
        lateinit var boundAddress: InetSocketAddress

        override fun publish(options: ShareOptions): PublishedMinecraftTransport {
            check(publishedPort == -1)
            publishCount++
            boundAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 25565)
            publishedPort = boundAddress.port
            listenerCount++
            return object : PublishedMinecraftTransport {
                override val address = boundAddress
                override val childInitializer = NoopInitializer

                override fun addLocalListener(listener: LocalShareChannel) {
                    listenerCount++
                }

                override fun removeLocalListener(listener: LocalShareChannel) {
                    listenerCount--
                }

                override fun close() {
                    if (publishedPort != -1) {
                        publishedPort = -1
                        listenerCount--
                    }
                }
            }
        }
    }

    private class FakeLocalChannelBinder : LocalShareChannelBinder {
        override fun bind(
            childInitializer: ChannelInitializer<Channel>,
        ): LocalShareChannel = object : LocalShareChannel {
            override val address: SocketAddress = LocalAddress("connect-share-26-2")
            override fun close() = Unit
        }
    }

    private object NoopInitializer : ChannelInitializer<Channel>() {
        override fun initChannel(channel: Channel) = Unit
    }

    private companion object {
        val options = ShareOptions(
            gameMode = ShareGameMode.SURVIVAL,
            allowCheats = false,
        )
    }
}
