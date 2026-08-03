package com.minekube.connect.share

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

class AdapterContractTest {
    @Test
    fun `every version bridge is loopback local repeatable and exactly released`() = runBlocking {
        val harness = FakeVersionTransport()
        val binder = FakeLocalBinder()
        val bridge = VersionedMinecraftBridge(harness, binder)

        val first = bridge.open(options)
        assertTrue(harness.boundAddress.address.isLoopbackAddress)
        assertIs<LocalAddress>(first.address)
        assertFailsWith<IllegalStateException> {
            bridge.open(options)
        }
        first.close()

        val second = bridge.open(options)
        assertTrue(harness.boundAddress.address.isLoopbackAddress)
        assertIs<LocalAddress>(second.address)
        second.close()

        assertEquals(-1, harness.publishedPort)
        assertEquals(0, harness.listenerCount)
        assertEquals(2, harness.publishCount)
        assertEquals(listOf(harness.eventLoopGroup, harness.eventLoopGroup), binder.eventLoopGroups)
        harness.eventLoopGroup.shutdownGracefully().syncUninterruptibly()
    }

    @Test
    fun `release continues through admission and local channel failures`() = runBlocking {
        val transport = FakeVersionTransport()
        val local = FailingLocalBinder()
        val bridge = VersionedMinecraftBridge(
            transport = transport,
            localBinder = local,
            loginAdmissionAcquire = {
                AutoCloseable {
                    throw IllegalStateException("admission close failed")
                }
            },
        )
        val target = bridge.open(options)

        val failure = assertFailsWith<IllegalStateException> {
            target.close()
        }

        assertEquals("admission close failed", failure.message)
        assertTrue(local.closed)
        assertEquals(1, transport.publishedCloseCount)
        assertEquals(-1, transport.publishedPort)
        assertEquals(0, transport.listenerCount)
    }

    private class FakeVersionTransport : MinecraftVersionTransport {
        var publishedPort = -1
        var listenerCount = 0
        var publishCount = 0
        var publishedCloseCount = 0
        val eventLoopGroup: EventLoopGroup = DefaultEventLoopGroup(1)
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
                override val eventLoopGroup = this@FakeVersionTransport.eventLoopGroup

                override fun addLocalListener(listener: LocalShareChannel) {
                    listenerCount++
                }

                override fun removeLocalListener(listener: LocalShareChannel) {
                    listenerCount--
                }

                override fun close() {
                    if (publishedPort != -1) {
                        publishedCloseCount++
                        publishedPort = -1
                        listenerCount--
                    }
                }
            }
        }
    }

    private class FailingLocalBinder : LocalShareChannelBinder {
        var closed = false

        override fun bind(
            childInitializer: ChannelInitializer<Channel>,
            eventLoopGroup: EventLoopGroup,
        ): LocalShareChannel = object : LocalShareChannel {
            override val address: SocketAddress = LocalAddress("failing-local")

            override fun close() {
                closed = true
                throw IllegalStateException("local close failed")
            }
        }
    }

    private class FakeLocalBinder : LocalShareChannelBinder {
        val eventLoopGroups = mutableListOf<EventLoopGroup>()

        override fun bind(
            childInitializer: ChannelInitializer<Channel>,
            eventLoopGroup: EventLoopGroup,
        ): LocalShareChannel {
            eventLoopGroups += eventLoopGroup
            return object : LocalShareChannel {
                override val address: SocketAddress = LocalAddress("adapter-contract")
                override fun close() = Unit
            }
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
