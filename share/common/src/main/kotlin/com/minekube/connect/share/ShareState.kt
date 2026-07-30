package com.minekube.connect.share

sealed interface ShareState {
    data object Idle : ShareState
    data object Starting : ShareState

    data class Sharing(
        val endpoint: String,
        val address: String,
    ) : ShareState

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
