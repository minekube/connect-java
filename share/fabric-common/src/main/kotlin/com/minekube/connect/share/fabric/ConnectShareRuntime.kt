package com.minekube.connect.share.fabric

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ConnectShareRuntime(
    private val scope: CoroutineScope,
    private val stopShare: suspend () -> Unit,
    private val resumeShare: suspend () -> Unit = {},
    private val worldAvailabilityChanged: (Boolean) -> Unit = {},
    private val lifecycleDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private val lifecycle = Mutex()
    private var currentWorldIdentity: Any? = null

    fun integratedWorldChanged(
        worldAvailable: Boolean,
        identity: Any? = if (worldAvailable) DEFAULT_WORLD_IDENTITY else null,
    ) {
        val transition = synchronized(lock) {
            val previous = currentWorldIdentity
            val current = if (worldAvailable) identity else null
            currentWorldIdentity = current
            if (previous == current) {
                null
            } else {
                WorldTransition(
                    stopPrevious = previous != null,
                    resumeCurrent = current != null,
                )
            }
        }
        if (transition == null) {
            worldAvailabilityChanged(worldAvailable)
            return
        }
        scope.launch(lifecycleDispatcher) {
            lifecycle.withLock {
                if (transition.stopPrevious) {
                    stopShare()
                }
                worldAvailabilityChanged(worldAvailable)
                if (transition.resumeCurrent) {
                    resumeShare()
                }
            }
        }
    }

    suspend fun shutdown() {
        val shouldStop = synchronized(lock) {
            (currentWorldIdentity != null).also {
                currentWorldIdentity = null
            }
        }
        withContext(lifecycleDispatcher) {
            worldAvailabilityChanged(false)
            if (shouldStop) {
                lifecycle.withLock {
                    stopShare()
                }
            }
        }
    }

    private data class WorldTransition(
        val stopPrevious: Boolean,
        val resumeCurrent: Boolean,
    )

    private companion object {
        val DEFAULT_WORLD_IDENTITY = Any()
    }
}
