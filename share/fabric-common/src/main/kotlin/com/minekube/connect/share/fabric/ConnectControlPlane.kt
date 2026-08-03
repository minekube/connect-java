package com.minekube.connect.share.fabric

import com.minekube.connect.share.identity.EndpointIdentity
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

class ConnectControlPlane(
    private val scope: CoroutineScope,
    private val ingress: PersistentConnectIngress,
    private val identity: suspend () -> EndpointIdentity,
    private val target: SocketAddress,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val failureReporter: (String) -> Unit = {},
) {
    private val startJob = AtomicReference<Job?>()

    val state = ingress.state

    fun start() {
        if (state.value == PersistentConnectState.Closed) {
            return
        }
        val launched = scope.launch(
            context = ioDispatcher,
            start = CoroutineStart.LAZY,
        ) {
            val result = ingress.startControl(identity(), target)
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

    suspend fun restart() {
        startJob.getAndSet(null)?.cancelAndJoin()
        withContext(ioDispatcher) {
            ingress.restart()
        }
        start()
    }
}
