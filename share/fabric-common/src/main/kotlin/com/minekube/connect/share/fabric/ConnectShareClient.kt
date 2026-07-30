package com.minekube.connect.share.fabric

import com.minekube.connect.share.ShareState
import com.minekube.connect.share.fabric.ui.ShareViewModel

fun interface ConnectShareScreenFactory {
    fun open(parent: Any, active: Boolean)
}

data class ConnectShareInstallation(
    val viewModel: ShareViewModel,
    val runtime: ConnectShareRuntime,
    val screens: ConnectShareScreenFactory,
)

object ConnectShareClient {
    @Volatile
    private var installation: ConnectShareInstallation? = null

    fun install(value: ConnectShareInstallation) {
        check(installation == null) {
            "Connect Share client is already installed"
        }
        installation = value
    }

    @JvmStatic
    fun isInstalled(): Boolean = installation != null

    @JvmStatic
    fun pauseButtonTranslationKey(): String =
        if (isShareActive()) {
            "connect_share.menu.active"
        } else {
            "connect_share.menu.share"
        }

    @JvmStatic
    fun openPauseScreen(parent: Any) {
        installation?.let { installed ->
            installed.screens.open(parent, isShareActive())
        }
    }

    @JvmStatic
    fun viewModel(): ShareViewModel =
        checkNotNull(installation).viewModel

    @JvmStatic
    fun integratedWorldChanged(
        worldAvailable: Boolean,
        identity: Any?,
    ) {
        installation?.runtime?.integratedWorldChanged(worldAvailable, identity)
    }

    @JvmStatic
    fun shutdown() {
        installation?.runtime?.shutdown()
    }

    private fun isShareActive(): Boolean = when (
        installation?.viewModel?.state?.value?.shareState
    ) {
        null,
        ShareState.Idle,
        is ShareState.Failed,
        -> false

        ShareState.Starting,
        is ShareState.Sharing,
        ShareState.Stopping,
        -> true
    }
}
