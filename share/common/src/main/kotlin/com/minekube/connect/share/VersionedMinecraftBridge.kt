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
import io.netty.channel.EventLoopGroup
import io.netty.channel.local.LocalAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

open class VersionedMinecraftBridge private constructor(
    private val transport: MinecraftVersionTransport,
    private val localBinder: LocalShareChannelBinder?,
    private val gateway: ShareConnectionGateway?,
    private val loginAdmissionAcquire: (() -> AutoCloseable)? = null,
) : CommonPlatformInjector(), MinecraftShareBridge {
    constructor(
        transport: MinecraftVersionTransport,
        localBinder: LocalShareChannelBinder,
        loginAdmissionAcquire: (() -> AutoCloseable)? = null,
    ) : this(
        transport = transport,
        localBinder = localBinder,
        gateway = null,
        loginAdmissionAcquire = loginAdmissionAcquire,
    )

    constructor(
        transport: MinecraftVersionTransport,
        gateway: ShareConnectionGateway,
        loginAdmissionAcquire: (() -> AutoCloseable)? = null,
    ) : this(
        transport = transport,
        localBinder = null,
        gateway = gateway,
        loginAdmissionAcquire = loginAdmissionAcquire,
    )

    private val lifecycleLock = Any()
    private var active: ActiveTransport? = null

    override suspend fun open(options: ShareOptions): LocalShareTarget = synchronized(lifecycleLock) {
        check(active == null) { "Connect Share is already active" }

        val published = transport.publish(options)
        var local: LocalShareChannel? = null
        var localAdded = false
        var gatewayLease: AutoCloseable? = null
        var admission: AutoCloseable? = null
        try {
            validatePublished(published).fold(
                ifLeft = { failure -> throw IllegalStateException(failure.safeMessage) },
                ifRight = {},
            )
            val connectAddress: SocketAddress
            val directAddress: InetSocketAddress
            if (gateway != null) {
                connectAddress = gateway.serverSocketAddress
                directAddress = gateway.directAddress
                validateGateway(connectAddress, directAddress).fold(
                    ifLeft = {
                        throw IllegalStateException(it.safeMessage)
                    },
                    ifRight = {},
                )
                gatewayLease = gateway.activateMinecraft(
                    published.childInitializer,
                )
            } else {
                local = checkNotNull(localBinder)
                    .bind(
                        published.childInitializer,
                        published.eventLoopGroup,
                    )
                validateLocal(local).fold(
                    ifLeft = {
                        throw IllegalStateException(it.safeMessage)
                    },
                    ifRight = {},
                )
                published.addLocalListener(local)
                localAdded = true
                connectAddress = local.address
                directAddress = published.address
            }
            admission = loginAdmissionAcquire?.invoke()
            val acquired = ActiveTransport(
                published = published,
                local = local,
                localAdded = localAdded,
                gatewayLease = gatewayLease,
                admission = admission,
            )
            active = acquired
            serverSocketAddress = connectAddress
            LocalShareTarget(
                address = connectAddress,
                directAddress = directAddress,
            ) {
                close(acquired)
            }
        } catch (failure: Throwable) {
            var cleanup: Throwable? = failure
            cleanup = releaseAfter(cleanup) {
                admission?.close()
            }
            cleanup = releaseAfter(cleanup) {
                gatewayLease?.close()
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

    private fun validateGateway(
        connectAddress: SocketAddress,
        directAddress: InetSocketAddress,
    ): Either<BridgeValidationError, Unit> = either {
        ensure(connectAddress is LocalAddress) {
            BridgeValidationError.NonLocalConnectTarget
        }
        ensure(directAddress.address.isLoopbackAddress) {
            BridgeValidationError.PublicListener
        }
    }

    private class ActiveTransport(
        private val published: PublishedMinecraftTransport,
        private val local: LocalShareChannel?,
        private val localAdded: Boolean,
        private val gatewayLease: AutoCloseable?,
        private val admission: AutoCloseable?,
    ) {
        private var admissionStopped = false
        private var routeClosed = false
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
            if (routeClosed) {
                return primary
            }
            routeClosed = true
            var failure = releaseAfter(primary) {
                gatewayLease?.close()
            }
            if (!localAdded || local == null) {
                return failure
            }
            failure = releaseAfter(failure) {
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
    val eventLoopGroup: EventLoopGroup

    fun addLocalListener(listener: LocalShareChannel)

    fun removeLocalListener(listener: LocalShareChannel)

    fun close()
}

fun interface LocalShareChannelBinder {
    fun bind(
        childInitializer: ChannelInitializer<Channel>,
        eventLoopGroup: EventLoopGroup,
    ): LocalShareChannel
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
        eventLoopGroup: EventLoopGroup,
    ): LocalShareChannel {
        try {
            val future = ServerBootstrap()
                .channel(LocalServerChannelWrapper::class.java)
                .childHandler(childInitializer)
                .group(eventLoopGroup)
                .localAddress(LocalAddress.ANY)
                .bind()
                .syncUninterruptibly()
            return NettyLocalShareChannel(future)
        } catch (failure: Throwable) {
            throw failure
        }
    }
}

private class NettyLocalShareChannel(
    override val future: ChannelFuture,
) : LocalShareChannel {
    override val address = future.channel().localAddress()

    override fun close() {
        future.closeChannel()
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
