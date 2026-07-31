package com.minekube.connect.share

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.minekube.connect.inject.CommonPlatformInjector
import com.minekube.connect.network.netty.LocalServerChannelWrapper
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.EventLoopGroup
import io.netty.channel.local.LocalAddress
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import java.net.SocketAddress

open class VersionedMinecraftBridge(
    private val transport: MinecraftVersionTransport,
    private val localBinder: LocalShareChannelBinder,
    private val loginAdmissionAcquire: (() -> AutoCloseable)? = null,
) : CommonPlatformInjector(), MinecraftShareBridge {
    private val lifecycleLock = Any()
    private var active: ActiveTransport? = null

    override suspend fun open(options: ShareOptions): LocalShareTarget = synchronized(lifecycleLock) {
        check(active == null) { "Connect Share is already active" }

        val published = transport.publish(options)
        var local: LocalShareChannel? = null
        var localAdded = false
        var admission: AutoCloseable? = null
        try {
            validatePublished(published).fold(
                ifLeft = { failure -> throw IllegalStateException(failure.safeMessage) },
                ifRight = {},
            )
            local = localBinder.bind(published.childInitializer)
            validateLocal(local).fold(
                ifLeft = { failure -> throw IllegalStateException(failure.safeMessage) },
                ifRight = {},
            )
            published.addLocalListener(local)
            localAdded = true
            admission = loginAdmissionAcquire?.invoke()
            val acquired = ActiveTransport(published, local, admission)
            active = acquired
            serverSocketAddress = local.address
            LocalShareTarget(
                address = local.address,
                directAddress = published.address,
            ) {
                close(acquired)
            }
        } catch (failure: Throwable) {
            var cleanup: Throwable? = failure
            cleanup = releaseAfter(cleanup) {
                admission?.close()
            }
            if (localAdded) {
                cleanup = releaseAfter(cleanup) {
                    published.removeLocalListener(checkNotNull(local))
                }
            }
            cleanup = releaseAfter(cleanup) {
                local?.close()
            }
            releaseAfter(cleanup) {
                published.close()
            }
            throw failure
        }
    }

    override fun inject(): Boolean = isInjected

    override fun isInjected(): Boolean = synchronized(lifecycleLock) {
        active != null
    }

    override fun shutdown() {
        synchronized(lifecycleLock) {
            val acquired = active ?: return
            var failure = acquired.stopAdmission(null)
            failure = acquired.closeLocal(failure)
            failure?.let { throw it }
        }
    }

    private fun close(acquired: ActiveTransport) {
        synchronized(lifecycleLock) {
            if (active !== acquired) {
                return
            }
            active = null
            acquired.close()
            serverSocketAddress = null
        }
    }

    private fun validatePublished(
        published: PublishedMinecraftTransport,
    ): Either<BridgeValidationError, Unit> = either {
        ensure(published.address.address.isLoopbackAddress) {
            BridgeValidationError.PublicListener
        }
    }

    private fun validateLocal(
        local: LocalShareChannel,
    ): Either<BridgeValidationError, Unit> = either {
        ensure(local.address is LocalAddress) {
            BridgeValidationError.NonLocalConnectTarget
        }
    }

    private class ActiveTransport(
        private val published: PublishedMinecraftTransport,
        private val local: LocalShareChannel,
        private val admission: AutoCloseable?,
    ) {
        private var admissionStopped = false
        private var localClosed = false
        private var publishedClosed = false

        fun stopAdmission(primary: Throwable?): Throwable? {
            if (admissionStopped) {
                return primary
            }
            admissionStopped = true
            return releaseAfter(primary) {
                admission?.close()
            }
        }

        fun closeLocal(primary: Throwable?): Throwable? {
            if (localClosed) {
                return primary
            }
            localClosed = true
            var failure = releaseAfter(primary) {
                published.removeLocalListener(local)
            }
            failure = releaseAfter(failure) {
                local.close()
            }
            return failure
        }

        fun close() {
            var failure = stopAdmission(null)
            failure = closeLocal(failure)
            if (!publishedClosed) {
                publishedClosed = true
                failure = releaseAfter(failure) {
                    published.close()
                }
            }
            failure?.let { throw it }
        }
    }
}

private inline fun releaseAfter(
    primary: Throwable?,
    release: () -> Unit,
): Throwable? = try {
    release()
    primary
} catch (releaseFailure: Throwable) {
    if (primary == null) {
        releaseFailure
    } else {
        if (releaseFailure !== primary) {
            primary.addSuppressed(releaseFailure)
        }
        primary
    }
}

fun interface MinecraftVersionTransport {
    fun publish(options: ShareOptions): PublishedMinecraftTransport
}

interface PublishedMinecraftTransport {
    val address: InetSocketAddress
    val childInitializer: ChannelInitializer<Channel>

    fun addLocalListener(listener: LocalShareChannel)

    fun removeLocalListener(listener: LocalShareChannel)

    fun close()
}

fun interface LocalShareChannelBinder {
    fun bind(childInitializer: ChannelInitializer<Channel>): LocalShareChannel
}

interface LocalShareChannel {
    val address: SocketAddress
    val future: ChannelFuture?
        get() = null

    fun close()
}

class NettyLocalShareChannelBinder : LocalShareChannelBinder {
    override fun bind(
        childInitializer: ChannelInitializer<Channel>,
    ): LocalShareChannel {
        val eventLoop = DefaultEventLoopGroup(
            0,
            DefaultThreadFactory(
                "Connect Share local",
                Thread.MAX_PRIORITY,
            ),
        )
        try {
            val future = ServerBootstrap()
                .channel(LocalServerChannelWrapper::class.java)
                .childHandler(childInitializer)
                .group(eventLoop)
                .localAddress(LocalAddress.ANY)
                .bind()
                .syncUninterruptibly()
            return NettyLocalShareChannel(future, eventLoop)
        } catch (failure: Throwable) {
            eventLoop.shutdownGracefully().syncUninterruptibly()
            throw failure
        }
    }
}

private class NettyLocalShareChannel(
    override val future: ChannelFuture,
    private val eventLoop: EventLoopGroup,
) : LocalShareChannel {
    override val address = future.channel().localAddress()

    override fun close() {
        future.closeChannel()
        eventLoop.shutdownGracefully().syncUninterruptibly()
    }
}

private fun ChannelFuture.closeChannel() {
    if (channel().isOpen) {
        channel().close().syncUninterruptibly()
    }
}

private sealed interface BridgeValidationError {
    val safeMessage: String

    data object PublicListener : BridgeValidationError {
        override val safeMessage = "Minecraft tried to open Connect Share beyond loopback"
    }

    data object NonLocalConnectTarget : BridgeValidationError {
        override val safeMessage = "Connect Share requires an in-process Minecraft target"
    }
}
