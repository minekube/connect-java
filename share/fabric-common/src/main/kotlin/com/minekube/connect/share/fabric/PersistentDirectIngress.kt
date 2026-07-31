package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.DirectShareHandle
import com.minekube.connect.share.DirectShareIngress
import com.minekube.connect.share.ShareOptions
import java.net.SocketAddress
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PersistentDirectState {
    data object Idle : PersistentDirectState

    data object Starting : PersistentDirectState

    data class Available(
        val lanAvailable: Boolean,
        val internetAvailable: Boolean,
    ) : PersistentDirectState

    data class Failed(
        val safeMessage: String,
    ) : PersistentDirectState

    data object Closed : PersistentDirectState
}

sealed interface PersistentDirectFailure {
    val safeMessage: String

    data object StartFailed : PersistentDirectFailure {
        override val safeMessage =
            "Direct friend delivery is temporarily unavailable"
    }

    data object Closed : PersistentDirectFailure {
        override val safeMessage =
            "Direct friend delivery has stopped"
    }
}

class PersistentDirectIngress(
    private val delegate: DirectShareIngress,
) : DirectShareIngress {
    private val lifecycle = Mutex()
    private var active: Active? = null
    private val mutableState = MutableStateFlow<PersistentDirectState>(
        PersistentDirectState.Idle,
    )

    val state: StateFlow<PersistentDirectState> =
        mutableState.asStateFlow()

    suspend fun startControl(
        options: ShareOptions,
        target: SocketAddress,
        connectAddress: String?,
    ): Either<PersistentDirectFailure, DirectShareHandle> =
        lifecycle.withLock {
            when {
                mutableState.value == PersistentDirectState.Closed ->
                    PersistentDirectFailure.Closed.left()

                active != null ->
                    checkNotNull(active)
                        .borrow(target, connectAddress)
                        .right()

                else -> {
                    mutableState.value = PersistentDirectState.Starting
                    try {
                        val acquired = delegate.start(
                            options,
                            target,
                            connectAddress,
                        )
                        val installed = Active(
                            target = target,
                            connectAddress = connectAddress,
                            handle = acquired,
                        )
                        active = installed
                        mutableState.value =
                            PersistentDirectState.Available(
                                lanAvailable = acquired.lanAvailable,
                                internetAvailable =
                                    acquired.internetAvailable,
                            )
                        installed.borrow(target, connectAddress).right()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        mutableState.value =
                            PersistentDirectState.Failed(
                                PersistentDirectFailure.StartFailed
                                    .safeMessage,
                            )
                        PersistentDirectFailure.StartFailed.left()
                    }
                }
            }
        }

    override suspend fun start(
        options: ShareOptions,
        target: SocketAddress,
        connectAddress: String?,
    ): DirectShareHandle = startControl(
        options,
        target,
        connectAddress,
    ).fold(
        ifLeft = {
            throw IllegalStateException(it.safeMessage)
        },
        ifRight = { it },
    )

    suspend fun shutdown() {
        lifecycle.withLock {
            if (mutableState.value == PersistentDirectState.Closed) {
                return@withLock
            }
            val acquired = active
            active = null
            try {
                acquired?.handle?.close?.invoke()
            } finally {
                mutableState.value = PersistentDirectState.Closed
            }
        }
    }

    private data class Active(
        val target: SocketAddress,
        val connectAddress: String?,
        val handle: DirectShareHandle,
    ) {
        fun borrow(
            requestedTarget: SocketAddress,
            requestedConnectAddress: String?,
        ): DirectShareHandle {
            check(requestedTarget == target) {
                "Persistent direct gateway target changed"
            }
            check(requestedConnectAddress == connectAddress) {
                "Persistent direct Connect fallback changed"
            }
            return handle.copy(close = {})
        }
    }
}
