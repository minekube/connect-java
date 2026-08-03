package com.minekube.connect.share

sealed interface ShareState {
    data object Idle : ShareState
    data object Starting : ShareState

    data class Sharing(
        val endpoint: String?,
        val address: String?,
        val invitation: String? = null,
        val connectAvailable: Boolean = true,
        val lanDirectAvailable: Boolean = false,
        val internetDirectAvailable: Boolean = false,
    ) : ShareState {
        override fun toString(): String =
            "Sharing(endpoint=$endpoint, address=$address, " +
                "invitation=<redacted>, " +
                "connectAvailable=$connectAvailable, " +
                "lanDirectAvailable=$lanDirectAvailable, " +
                "internetDirectAvailable=$internetDirectAvailable)"
    }

    data object Stopping : ShareState

    data class Failed(
        val safeMessage: String,
    ) : ShareState
}

sealed interface ShareLifecycleError {
    val safeMessage: String

    data object AlreadyActive : ShareLifecycleError {
        override val safeMessage: String = "A Connect Share operation is already active"
    }

    data object StartFailed : ShareLifecycleError {
        override val safeMessage: String = "Could not start Connect Share"
    }

    data object StopFailed : ShareLifecycleError {
        override val safeMessage: String = "Connect Share stopped with cleanup errors"
    }
}
