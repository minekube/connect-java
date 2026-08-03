package com.minekube.connect.share

import com.minekube.connect.inject.CommonPlatformInjector
import com.minekube.connect.network.netty.LocalServerChannelWrapper
import com.minekube.connect.share.direct.DirectSessionAttributes
import com.minekube.connect.share.direct.DirectSessionRegistry
import com.minekube.connect.share.friend.FriendControlChannelHandler
import com.minekube.connect.share.friend.FriendControlServer
import com.minekube.connect.share.friend.friendControlContext
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ThreadFactory

class ShareConnectionGateway private constructor(
    private val friendServer: FriendControlServer,
    minecraftThreadFactory: ThreadFactory?,
) : CommonPlatformInjector(), AutoCloseable {
    private val activeMinecraft =
        AtomicReference<ChannelInitializer<Channel>?>(null)
    private val closed = AtomicBoolean()
    private val localEventLoop: EventLoopGroup = DefaultEventLoopGroup(
        1,
        minecraftThreadFactory
            ?: DefaultThreadFactory("Connect Share gateway local"),
    )
    private val directEventLoop: EventLoopGroup = NioEventLoopGroup(
        1,
        minecraftThreadFactory
            ?: DefaultThreadFactory("Connect Share gateway direct"),
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
                    MINECRAFT_STATUS_PRIVACY_HANDLER,
                    MinecraftStatusPrivacyHandler(friendServer),
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

    private class MinecraftStatusPrivacyHandler(
        private val friendServer: FriendControlServer,
    ) : ChannelInboundHandlerAdapter() {
        private val buffered = ByteArrayOutputStream()

        override fun channelRead(
            context: ChannelHandlerContext,
            message: Any,
        ) {
            if (message !is ByteBuf) {
                context.fireChannelRead(message)
                return
            }
            try {
                val bytes = ByteArray(message.readableBytes())
                message.readBytes(bytes)
                buffered.write(bytes)
            } finally {
                ReferenceCountUtil.release(message)
            }
            if (buffered.size() > MAX_MINECRAFT_HANDSHAKE_BYTES) {
                context.close()
                return
            }
            val bytes = buffered.toByteArray()
            when (val decoded = MinecraftHandshake.decode(bytes)) {
                MinecraftHandshakeDecode.Incomplete -> Unit
                MinecraftHandshakeDecode.Invalid -> context.close()
                is MinecraftHandshakeDecode.Decoded -> {
                    if (
                        decoded.intent == MinecraftHandshakeIntent.STATUS &&
                        !friendServer.allowsMinecraftStatus(
                            context.channel().friendControlContext(),
                        )
                    ) {
                        context.close()
                    } else {
                        context.pipeline().remove(this)
                        context.fireChannelRead(Unpooled.wrappedBuffer(bytes))
                    }
                }
            }
        }
    }

    private enum class MinecraftHandshakeIntent {
        STATUS,
        LOGIN,
    }

    private sealed interface MinecraftHandshakeDecode {
        data object Incomplete : MinecraftHandshakeDecode
        data object Invalid : MinecraftHandshakeDecode
        data class Decoded(
            val intent: MinecraftHandshakeIntent,
        ) : MinecraftHandshakeDecode
    }

    private object MinecraftHandshake {
        fun decode(bytes: ByteArray): MinecraftHandshakeDecode {
            val frameLength = readVarInt(bytes, 0)
                ?: return MinecraftHandshakeDecode.Incomplete
            if (frameLength.value < 0 || frameLength.value > MAX_MINECRAFT_HANDSHAKE_BYTES) {
                return MinecraftHandshakeDecode.Invalid
            }
            val frameEnd = frameLength.next + frameLength.value
            if (frameEnd > bytes.size) {
                return MinecraftHandshakeDecode.Incomplete
            }
            var cursor = frameLength.next
            val packetId = readVarInt(bytes, cursor)
                ?: return MinecraftHandshakeDecode.Invalid
            if (packetId.value != 0) return MinecraftHandshakeDecode.Invalid
            cursor = packetId.next
            val protocol = readVarInt(bytes, cursor)
                ?: return MinecraftHandshakeDecode.Invalid
            cursor = protocol.next
            val addressLength = readVarInt(bytes, cursor)
                ?: return MinecraftHandshakeDecode.Invalid
            if (addressLength.value !in 0..MAX_SERVER_ADDRESS_BYTES) {
                return MinecraftHandshakeDecode.Invalid
            }
            cursor = addressLength.next + addressLength.value
            if (cursor + PORT_BYTES > frameEnd) {
                return MinecraftHandshakeDecode.Invalid
            }
            cursor += PORT_BYTES
            val intent = readVarInt(bytes, cursor)
                ?: return MinecraftHandshakeDecode.Invalid
            if (intent.next != frameEnd) return MinecraftHandshakeDecode.Invalid
            return when (intent.value) {
                1 -> MinecraftHandshakeDecode.Decoded(
                    MinecraftHandshakeIntent.STATUS,
                )
                2, 3 -> MinecraftHandshakeDecode.Decoded(
                    MinecraftHandshakeIntent.LOGIN,
                )
                else -> MinecraftHandshakeDecode.Invalid
            }
        }

        private fun readVarInt(
            bytes: ByteArray,
            start: Int,
        ): DecodedVarInt? {
            var value = 0
            var position = 0
            var cursor = start
            while (position < MAX_VAR_INT_BITS) {
                if (cursor >= bytes.size) return null
                val current = bytes[cursor].toInt() and 0xff
                value = value or ((current and 0x7f) shl position)
                cursor++
                if (current and 0x80 == 0) {
                    return DecodedVarInt(value, cursor)
                }
                position += 7
            }
            return null
        }

        private data class DecodedVarInt(
            val value: Int,
            val next: Int,
        )
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
        fun bind(friendServer: FriendControlServer): ShareConnectionGateway =
            ShareConnectionGateway(friendServer, null)

        fun bind(
            minecraftThreadFactory: ThreadFactory?,
            friendServer: FriendControlServer,
        ):
            ShareConnectionGateway =
            ShareConnectionGateway(friendServer, minecraftThreadFactory)

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
        private const val MINECRAFT_STATUS_PRIVACY_HANDLER =
            "connect-share-minecraft-status-privacy"
        private const val MINECRAFT_INITIALIZER =
            "connect-share-minecraft-initializer"
        private const val MINECRAFT_LIFECYCLE_REPLAY =
            "connect-share-minecraft-lifecycle-replay"
        private const val MAX_MINECRAFT_HANDSHAKE_BYTES = 8_192
        private const val MAX_SERVER_ADDRESS_BYTES = 255
        private const val PORT_BYTES = 2
        private const val MAX_VAR_INT_BITS = 35
    }
}
