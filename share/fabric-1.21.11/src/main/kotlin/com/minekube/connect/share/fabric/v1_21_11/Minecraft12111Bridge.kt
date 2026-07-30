package com.minekube.connect.share.fabric.v1_21_11

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.minekube.connect.inject.CommonPlatformInjector
import com.minekube.connect.share.LocalShareTarget
import com.minekube.connect.share.MinecraftShareBridge
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.fabric.FabricLocalLoginAdmissionGate
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.local.LocalAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

class Minecraft12111Bridge internal constructor(
    private val transport: Minecraft12111Transport,
    private val localBinder: LocalShareChannelBinder,
    private val loginAdmissionFactory: (() -> FabricLocalLoginAdmissionGate)? = null,
) : CommonPlatformInjector(), MinecraftShareBridge {
    constructor() : this(
        VanillaMinecraft12111Transport(),
        NettyLocalShareChannelBinder(),
    )

    constructor(
        loginAdmissionFactory: () -> FabricLocalLoginAdmissionGate,
    ) : this(
        VanillaMinecraft12111Transport(),
        NettyLocalShareChannelBinder(),
        loginAdmissionFactory,
    )

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
            admission = loginAdmissionFactory
                ?.invoke()
                ?.let(Minecraft12111LoginAdmission::install)
            val acquired = ActiveTransport(published, local, admission)
            active = acquired
            serverSocketAddress = local.address
            LocalShareTarget(local.address) {
                close(acquired)
            }
        } catch (failure: Throwable) {
            admission?.close()
            if (localAdded) {
                published.removeLocalListener(checkNotNull(local))
            }
            local?.close()
            published.close()
            throw failure
        }
    }

    override fun inject(): Boolean = synchronized(lifecycleLock) {
        active != null
    }

    override fun isInjected(): Boolean = synchronized(lifecycleLock) {
        active != null
    }

    override fun shutdown() {
        synchronized(lifecycleLock) {
            active?.stopAdmission()
            active?.closeLocal()
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

        fun stopAdmission() {
            if (admissionStopped) {
                return
            }
            admissionStopped = true
            admission?.close()
        }

        fun closeLocal() {
            if (localClosed) {
                return
            }
            localClosed = true
            published.removeLocalListener(local)
            local.close()
        }

        fun close() {
            stopAdmission()
            closeLocal()
            published.close()
        }
    }
}

internal fun interface Minecraft12111Transport {
    fun publish(options: ShareOptions): PublishedMinecraftTransport
}

internal interface PublishedMinecraftTransport {
    val address: InetSocketAddress
    val childInitializer: ChannelInitializer<Channel>

    fun addLocalListener(listener: LocalShareChannel)

    fun removeLocalListener(listener: LocalShareChannel)

    fun close()
}

internal fun interface LocalShareChannelBinder {
    fun bind(childInitializer: ChannelInitializer<Channel>): LocalShareChannel
}

internal interface LocalShareChannel {
    val address: SocketAddress

    fun close()
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
