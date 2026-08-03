package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.ConnectShareHandle
import com.minekube.connect.share.ConnectShareIngress
import com.minekube.connect.share.identity.EndpointIdentity
import java.net.SocketAddress
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PersistentConnectState {
    data object Idle : PersistentConnectState

    data object Starting : PersistentConnectState

    data class Available(
        val endpoint: String,
        val publicAddress: String,
    ) : PersistentConnectState

    data class Failed(
        val safeMessage: String,
    ) : PersistentConnectState

    data object Closed : PersistentConnectState
}

sealed interface PersistentConnectFailure {
    val safeMessage: String

    data object StartFailed : PersistentConnectFailure {
        override val safeMessage =
            "Connect friend delivery is temporarily unavailable"
    }

    data object Closed : PersistentConnectFailure {
        override val safeMessage =
            "Connect friend delivery has stopped"
    }
}

class PersistentConnectIngress(
    private val delegate: ConnectShareIngress,
) : ConnectShareIngress {
    private val lifecycle = Mutex()
    private var active: Active? = null
    private val mutableState = MutableStateFlow<PersistentConnectState>(
        PersistentConnectState.Idle,
    )

    val state: StateFlow<PersistentConnectState> =
        mutableState.asStateFlow()

    suspend fun startControl(
        identity: EndpointIdentity,
        target: SocketAddress,
    ): Either<PersistentConnectFailure, ConnectShareHandle> =
        lifecycle.withLock {
            when {
                mutableState.value == PersistentConnectState.Closed ->
                    PersistentConnectFailure.Closed.left()

                active != null ->
                    checkNotNull(active)
                        .borrow(identity, target)
                        .right()

                else -> {
                    mutableState.value = PersistentConnectState.Starting
                    try {
                        val acquired = delegate.start(identity, target)
                        val installed = Active(identity, target, acquired)
                        active = installed
                        mutableState.value =
                            PersistentConnectState.Available(
                                endpoint = acquired.endpoint,
                                publicAddress = acquired.publicAddress,
                            )
                        installed.borrow(identity, target).right()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        mutableState.value =
                            PersistentConnectState.Failed(
                                PersistentConnectFailure.StartFailed
                                    .safeMessage,
                            )
                        PersistentConnectFailure.StartFailed.left()
                    }
                }
            }
        }

    override suspend fun start(
        identity: EndpointIdentity,
        target: SocketAddress,
    ): ConnectShareHandle = startControl(identity, target).fold(
        ifLeft = {
            throw IllegalStateException(it.safeMessage)
        },
        ifRight = { it },
    )

    suspend fun shutdown() {
        lifecycle.withLock {
            if (mutableState.value == PersistentConnectState.Closed) {
                return@withLock
            }
            val acquired = active
            active = null
            try {
                acquired?.handle?.close?.invoke()
            } finally {
                mutableState.value = PersistentConnectState.Closed
            }
        }
    }

    suspend fun restart() {
        lifecycle.withLock {
            if (mutableState.value == PersistentConnectState.Closed) {
                return@withLock
            }
            val acquired = active
            active = null
            try {
                acquired?.handle?.close?.invoke()
            } finally {
                mutableState.value = PersistentConnectState.Idle
            }
        }
    }

    private data class Active(
        val identity: EndpointIdentity,
        val target: SocketAddress,
        val handle: ConnectShareHandle,
    ) {
        fun borrow(
            requestedIdentity: EndpointIdentity,
            requestedTarget: SocketAddress,
        ): ConnectShareHandle {
            check(requestedIdentity == identity) {
                "Persistent Connect endpoint identity changed"
            }
            check(requestedTarget == target) {
                "Persistent Connect gateway target changed"
            }
            return ConnectShareHandle(
                endpoint = handle.endpoint,
                publicAddress = handle.publicAddress,
                close = {},
            )
        }
    }
}
