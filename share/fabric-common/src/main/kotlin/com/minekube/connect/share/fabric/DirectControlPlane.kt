package com.minekube.connect.share.fabric

import com.minekube.connect.share.ShareOptions
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DirectControlPlane(
    private val scope: CoroutineScope,
    private val ingress: PersistentDirectIngress,
    private val options: ShareOptions,
    private val target: SocketAddress,
    private val connectAddress: suspend () -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val failureReporter: (String) -> Unit = {},
) {
    private val startJob = AtomicReference<Job?>()

    val state = ingress.state

    fun start() {
        if (state.value == PersistentDirectState.Closed) {
            return
        }
        val launched = scope.launch(
            context = ioDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            val result = ingress.startControl(
                options = options,
                target = target,
                connectAddress = connectAddress(),
            )
            result.leftOrNull()?.let {
                failureReporter(it.safeMessage)
            }
        }
        if (!startJob.compareAndSet(null, launched)) {
            launched.cancel()
            return
        }
        launched.invokeOnCompletion {
            startJob.compareAndSet(launched, null)
        }
        launched.start()
    }

    suspend fun shutdown() {
        startJob.getAndSet(null)?.cancelAndJoin()
        withContext(ioDispatcher) {
            ingress.shutdown()
        }
    }
}
