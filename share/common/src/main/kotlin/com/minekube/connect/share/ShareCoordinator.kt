package com.minekube.connect.share

import arrow.core.Either
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.ExitCase.Companion.ExitCase
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.allocate
import arrow.fx.coroutines.resource
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.identity.EndpointIdentity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ShareCoordinator(
    private val bridge: MinecraftShareBridge,
    private val ingress: ConnectShareIngress,
    private val identityProvider: suspend () -> EndpointIdentity,
    private val admission: AdmissionController,
    private val directIngress: DirectShareIngress? = null,
    private val failureReporter: (String) -> Unit = {},
) {
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow<ShareState>(ShareState.Idle)
    private var active: ActiveShare? = null

    val state: StateFlow<ShareState> = mutableState.asStateFlow()

    suspend fun start(
        options: ShareOptions,
    ): Either<ShareLifecycleError, ShareState.Sharing> = lifecycleMutex.withLock {
        if (
            active != null ||
            mutableState.value == ShareState.Starting ||
            mutableState.value == ShareState.Stopping
        ) {
            return@withLock Either.Left(ShareLifecycleError.AlreadyActive)
        }
        mutableState.value = ShareState.Starting

        try {
            val managedShare = resource {
                val target = install(
                    acquire = { bridge.open(options) },
                    release = { acquired, _ -> acquired.close() },
                )
                var connectFailed = false
                val connect = try {
                    val identity = identityProvider()
                    install(
                        acquire = { ingress.start(identity, target.address) },
                        release = { acquired, _ -> acquired.close() },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    connectFailed = true
                    null
                }
                var directFailed = false
                val direct = try {
                    directIngress?.let {
                        install(
                            acquire = {
                                it.start(
                                    options = options,
                                    target = target.directAddress,
                                    connectAddress = connect?.publicAddress,
                                )
                            },
                            release = { acquired, _ -> acquired.close() },
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    directFailed = true
                    null
                }
                check(connect != null || direct != null) {
                    "Connect Share has no usable ingress"
                }
                when {
                    connectFailed -> reportFailure(CONNECT_DEGRADED_REPORT)
                    directFailed -> reportFailure(DIRECT_DEGRADED_REPORT)
                }
                AcquiredShare(target, connect, direct)
            }
            val (acquired, release) = managedShare.allocateSafely()
            val sharing = ShareState.Sharing(
                endpoint = acquired.connect?.endpoint,
                address = acquired.connect?.publicAddress,
                invitation = acquired.direct?.invitation,
                connectAvailable = acquired.connect != null,
                lanDirectAvailable = acquired.direct?.lanAvailable == true,
                internetDirectAvailable =
                    acquired.direct?.internetAvailable == true,
            )
            active = ActiveShare(release)
            mutableState.value = sharing
            Either.Right(sharing)
        } catch (cancellation: CancellationException) {
            mutableState.value = ShareState.Idle
            throw cancellation
        } catch (_: Exception) {
            mutableState.value = ShareState.Failed(
                ShareLifecycleError.StartFailed.safeMessage,
            )
            reportFailure(START_FAILURE_REPORT)
            Either.Left(ShareLifecycleError.StartFailed)
        }
    }

    suspend fun stop(): Either<ShareLifecycleError, Unit> {
        val share = lifecycleMutex.withLock {
            when {
                active != null -> {
                    mutableState.value = ShareState.Stopping
                    active.also { active = null }
                }

                mutableState.value == ShareState.Stopping -> return Either.Right(Unit)

                else -> {
                    admission.resetShare()
                    mutableState.value = ShareState.Idle
                    return Either.Right(Unit)
                }
            }
        } ?: return Either.Right(Unit)

        var cleanupFailure: Throwable? = null
        var cancellation: CancellationException? = null
        withContext(NonCancellable) {
            try {
                share.release(ExitCase.Completed)
            } catch (failure: CancellationException) {
                cancellation = failure
            } catch (failure: Exception) {
                cleanupFailure = failure
            } finally {
                admission.resetShare()
                lifecycleMutex.withLock {
                    mutableState.value = ShareState.Idle
                }
            }
        }
        cancellation?.let { throw it }
        return if (cleanupFailure == null) {
            Either.Right(Unit)
        } else {
            reportFailure(STOP_FAILURE_REPORT)
            Either.Left(ShareLifecycleError.StopFailed)
        }
    }

    suspend fun worldReplaced(): Either<ShareLifecycleError, Unit> = stop()

    private data class AcquiredShare(
        val target: LocalShareTarget,
        val connect: ConnectShareHandle?,
        val direct: DirectShareHandle?,
    )

    private data class ActiveShare(
        val release: suspend (ExitCase) -> Unit,
    )

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private suspend fun <A> Resource<A>.allocateSafely(): Pair<A, suspend (ExitCase) -> Unit> {
        val scopeResource: Resource<ResourceScope> = resource { this }
        val (scope, releaseAll) = scopeResource.allocate()
        return try {
            with(scope) {
                this@allocateSafely.bind()
            } to releaseAll
        } catch (failure: Throwable) {
            try {
                releaseAll(ExitCase(failure))
            } catch (releaseFailure: Throwable) {
                if (releaseFailure !== failure) {
                    failure.addSuppressed(releaseFailure)
                }
            }
            throw failure
        }
    }

    private fun reportFailure(safeMessage: String) {
        try {
            failureReporter(safeMessage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: RuntimeException) {
            // Reporting must not leave lifecycle state half-transitioned.
        }
    }

    private companion object {
        const val START_FAILURE_REPORT = "Connect Share start failed"
        const val STOP_FAILURE_REPORT = "Connect Share cleanup failed"
        const val CONNECT_DEGRADED_REPORT =
            "Connect Share started without Minekube Connect ingress"
        const val DIRECT_DEGRADED_REPORT =
            "Connect Share started without direct P2P ingress"
    }
}
