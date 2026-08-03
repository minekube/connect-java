package com.minekube.connect.share.fabric.v1_21_1

import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.fabric.v1_21_1.mixin.IntegratedServerAccessor
import com.minekube.connect.share.fabric.v1_21_1.mixin.LanServerPingerAccessor
import com.minekube.connect.share.fabric.v1_21_1.mixin.ServerConnectionListenerAccessor
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import java.net.InetSocketAddress
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.util.HttpUtil
import net.minecraft.world.level.GameType

internal class VanillaMinecraft1211Transport(
    private val serverProvider: () -> IntegratedServer? = {
        Minecraft.getInstance().singleplayerServer
    },
) : Minecraft1211Transport {
    override fun publish(options: ShareOptions): PublishedMinecraftTransport {
        val server = checkNotNull(serverProvider()) {
            "Connect Share requires an active singleplayer world"
        }
        check(!server.isPublished) {
            "This singleplayer world is already published"
        }
        val connection = server.connection
        val channels = (connection as ServerConnectionListenerAccessor)
            .connectShareChannels
        val before = synchronized(channels) { channels.toSet() }
        val captureLease = CapturedServerTransport.arm()
        var published = false
        try {
            published = server.publishServer(
                options.gameMode.toMinecraft(),
                options.allowCheats,
                HttpUtil.getAvailablePort(),
            )
            check(published) { "Minecraft could not start its private Share listener" }
            val captured = captureLease.complete().fold(
                ifLeft = {
                    throw IllegalStateException(
                        "Minecraft did not expose its Share channel initializer",
                    )
                },
                ifRight = { it },
            )
            val added = synchronized(channels) {
                channels.filterNot(before::contains)
            }
            check(added.size == 1) {
                "Minecraft created ${added.size} listeners for one Share start"
            }
            val loopback = added.single()
            val address = loopback.channel().localAddress() as? InetSocketAddress
                ?: throw IllegalStateException(
                    "Minecraft Share did not create a TCP listener",
                )
            check(address.address.isLoopbackAddress) {
                "Minecraft Share listener escaped loopback"
            }
            suppressLanAdvertisement(server)
            return PublishedVanillaTransport(
                server = server,
                channels = channels,
                loopback = loopback,
                address = address,
                childInitializer = captured.childInitializer,
                eventLoopGroup = captured.eventLoopGroup,
            )
        } catch (failure: Throwable) {
            captureLease.close()
            rollbackNewListeners(server, channels, before)
            if (published) {
                suppressLanAdvertisement(server)
            }
            throw failure
        }
    }

    private fun rollbackNewListeners(
        server: IntegratedServer,
        channels: MutableList<ChannelFuture>,
        before: Set<ChannelFuture>,
    ) {
        val added = synchronized(channels) {
            channels.filterNot(before::contains).also(channels::removeAll)
        }
        added.forEach(ChannelFuture::closeChannel)
        (server as IntegratedServerAccessor).setConnectSharePublishedPort(-1)
    }

    private fun suppressLanAdvertisement(server: IntegratedServer) {
        val accessor = server as IntegratedServerAccessor
        accessor.connectShareLanPinger?.let { pinger ->
            pinger.interrupt()
            (pinger as LanServerPingerAccessor).connectShareSocket.close()
        }
        accessor.connectShareLanPinger = null
    }

    private fun ShareGameMode.toMinecraft(): GameType = when (this) {
        ShareGameMode.SURVIVAL -> GameType.SURVIVAL
        ShareGameMode.CREATIVE -> GameType.CREATIVE
        ShareGameMode.ADVENTURE -> GameType.ADVENTURE
        ShareGameMode.SPECTATOR -> GameType.SPECTATOR
    }
}

private class PublishedVanillaTransport(
    private val server: IntegratedServer,
    private val channels: MutableList<ChannelFuture>,
    private val loopback: ChannelFuture,
    override val address: InetSocketAddress,
    override val childInitializer: ChannelInitializer<Channel>,
    override val eventLoopGroup: EventLoopGroup,
) : PublishedMinecraftTransport {
    override fun addLocalListener(listener: LocalShareChannel) {
        val future = checkNotNull(listener.future) {
            "Connect Share local channel did not expose its bound future"
        }
        synchronized(channels) {
            check(channels.add(future)) {
                "Minecraft already tracks the Connect Share local listener"
            }
        }
    }

    override fun removeLocalListener(listener: LocalShareChannel) {
        val future = listener.future ?: return
        synchronized(channels) {
            channels.remove(future)
        }
    }

    override fun close() {
        synchronized(channels) {
            channels.remove(loopback)
        }
        loopback.closeChannel()
        (server as IntegratedServerAccessor).setConnectSharePublishedPort(-1)
    }
}

private fun ChannelFuture.closeChannel() {
    if (channel().isOpen) {
        channel().close().syncUninterruptibly()
    }
}
