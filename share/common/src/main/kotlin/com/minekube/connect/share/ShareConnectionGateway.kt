package com.minekube.connect.share

import com.minekube.connect.inject.CommonPlatformInjector
import com.minekube.connect.network.netty.LocalServerChannelWrapper
import com.minekube.connect.share.direct.DirectSessionAttributes
import com.minekube.connect.share.direct.DirectSessionRegistry
import com.minekube.connect.share.friend.FriendControlChannelHandler
import com.minekube.connect.share.friend.FriendControlServer
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.EventLoopGroup
import io.netty.channel.local.LocalAddress
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ShareConnectionGateway private constructor(
    private val friendServer: FriendControlServer,
) : CommonPlatformInjector(), AutoCloseable {
    private val activeMinecraft =
        AtomicReference<ChannelInitializer<Channel>?>(null)
    private val closed = AtomicBoolean()
    private val localEventLoop: EventLoopGroup = DefaultEventLoopGroup(
        1,
        DefaultThreadFactory("Connect Share gateway local"),
    )
    private val directEventLoop: EventLoopGroup = NioEventLoopGroup(
        1,
        DefaultThreadFactory("Connect Share gateway direct"),
    )
    private val directChannel: ChannelFuture

    val directAddress: InetSocketAddress
        get() = directChannel.channel().localAddress() as InetSocketAddress

    val isClosed: Boolean
        get() = closed.get()

    init {
        try {
            localChannel = bindLocal()
            serverSocketAddress = localChannel.channel().localAddress()
            directChannel = bindDirect()
        } catch (failure: Throwable) {
            closeAfterFailedBind()
            throw failure
        }
    }

    fun activateMinecraft(
        initializer: ChannelInitializer<Channel>,
    ): AutoCloseable {
        check(!closed.get()) { "Connect Share gateway is closed" }
        check(activeMinecraft.compareAndSet(null, initializer)) {
            "A Minecraft world is already active"
        }
        return AutoCloseable {
            activeMinecraft.compareAndSet(initializer, null)
        }
    }

    override fun inject(): Boolean = !closed.get()

    override fun isInjected(): Boolean =
        !closed.get() &&
            localChannel?.channel()?.isOpen == true &&
            directChannel.channel().isOpen

    override fun shutdown() {
        // The embedded Connect runtime borrows this injector. The gateway owns
        // both listeners and releases them from close(), after every borrower.
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        activeMinecraft.set(null)
        closeChannel(directChannel)
        closeChannel(localChannel)
        localChannel = null
        shutdownEventLoop(directEventLoop)
        shutdownEventLoop(localEventLoop)
    }

    private fun bindLocal(): ChannelFuture =
        ServerBootstrap()
            .channel(LocalServerChannelWrapper::class.java)
            .childHandler(gatewayInitializer())
            .group(localEventLoop)
            .localAddress(LocalAddress.ANY)
            .bind()
            .syncUninterruptibly()

    private fun bindDirect(): ChannelFuture =
        ServerBootstrap()
            .channel(NioServerSocketChannel::class.java)
            .childHandler(gatewayInitializer())
            .group(directEventLoop)
            .localAddress(
                InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            )
            .bind()
            .syncUninterruptibly()

    private fun gatewayInitializer() =
        object : ChannelInitializer<Channel>() {
            override fun initChannel(channel: Channel) {
                DirectSessionRegistry.claim(channel.remoteAddress())?.let {
                    channel.attr(DirectSessionAttributes.SESSION).set(it)
                }
                channel.pipeline().addLast(
                    FRIEND_CONTROL_HANDLER,
                    FriendControlChannelHandler(friendServer),
                )
                channel.pipeline().addLast(
                    MINECRAFT_DISPATCH_HANDLER,
                    MinecraftDispatchHandler(activeMinecraft),
                )
            }
        }

    private fun closeAfterFailedBind() {
        runCatching { closeChannel(localChannel) }
        localChannel = null
        shutdownEventLoop(directEventLoop)
        shutdownEventLoop(localEventLoop)
    }

    private class MinecraftDispatchHandler(
        private val active:
            AtomicReference<ChannelInitializer<Channel>?>,
    ) : ChannelInboundHandlerAdapter() {
        override fun channelRead(
            context: ChannelHandlerContext,
            message: Any,
        ) {
            val initializer = active.get()
            if (initializer == null) {
                ReferenceCountUtil.release(message)
                context.close()
                return
            }
            val pipeline = context.pipeline()
            pipeline.remove(this)
            pipeline.addLast(
                MINECRAFT_LIFECYCLE_REPLAY,
                ChannelInboundHandlerAdapter(),
            )
            pipeline.addLast(MINECRAFT_INITIALIZER, initializer)
            checkNotNull(
                pipeline.context(MINECRAFT_LIFECYCLE_REPLAY),
            ).fireChannelActive()
            pipeline.remove(MINECRAFT_LIFECYCLE_REPLAY)
            pipeline.fireChannelRead(message)
        }
    }

    companion object {
        fun bind(friendServer: FriendControlServer):
            ShareConnectionGateway =
            ShareConnectionGateway(friendServer)

        private fun closeChannel(future: ChannelFuture?) {
            val channel = future?.channel() ?: return
            if (channel.isOpen) {
                channel.close().syncUninterruptibly()
            }
        }

        private fun shutdownEventLoop(group: EventLoopGroup) {
            group.shutdownGracefully().syncUninterruptibly()
        }

        private const val FRIEND_CONTROL_HANDLER =
            "connect-share-friend-control"
        private const val MINECRAFT_DISPATCH_HANDLER =
            "connect-share-minecraft-dispatch"
        private const val MINECRAFT_INITIALIZER =
            "connect-share-minecraft-initializer"
        private const val MINECRAFT_LIFECYCLE_REPLAY =
            "connect-share-minecraft-lifecycle-replay"
    }
}
