package com.minekube.connect.share.fabric

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

class ConnectShareRuntime(
    private val scope: CoroutineScope,
    private val stopShare: suspend () -> Unit,
    private val worldAvailabilityChanged: (Boolean) -> Unit = {},
) {
    private val lock = Any()
    private var currentWorldIdentity: Any? = null

    fun integratedWorldChanged(
        worldAvailable: Boolean,
        identity: Any? = if (worldAvailable) DEFAULT_WORLD_IDENTITY else null,
    ) {
        val shouldStop = synchronized(lock) {
            val previous = currentWorldIdentity
            currentWorldIdentity = if (worldAvailable) identity else null
            previous != null &&
                (!worldAvailable || previous != currentWorldIdentity)
        }
        worldAvailabilityChanged(worldAvailable)
        if (shouldStop) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                stopShare()
            }
        }
    }

    fun shutdown() {
        val shouldStop = synchronized(lock) {
            (currentWorldIdentity != null).also {
                currentWorldIdentity = null
            }
        }
        worldAvailabilityChanged(false)
        if (shouldStop) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                stopShare()
            }
        }
    }

    private companion object {
        val DEFAULT_WORLD_IDENTITY = Any()
    }
}
