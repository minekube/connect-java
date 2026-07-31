package com.minekube.connect.share.fabric

import com.minekube.connect.share.ShareState
import com.minekube.connect.share.fabric.ui.ShareViewModel

fun interface ConnectShareScreenFactory {
    fun open(parent: Any, active: Boolean)
}

fun interface ConnectShareGuestScreenFactory {
    fun open(parent: Any)
}

data class ConnectShareInstallation(
    val viewModel: ShareViewModel,
    val runtime: ConnectShareRuntime,
    val friendCardIssuer: FriendCardIssuer,
    val approvedJoins: ApprovedJoinTracker,
    val screens: ConnectShareScreenFactory,
    val guestScreens: ConnectShareGuestScreenFactory,
)

object ConnectShareClient {
    @Volatile
    private var installation: ConnectShareInstallation? = null
    private val guestLease = GuestConnectionLease()
    private val friendCardConsent = FriendCardExchangeConsent()

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
    fun openJoinScreen(parent: Any) {
        installation?.guestScreens?.open(parent)
    }

    fun holdGuestDirect(
        target: GuestJoinTarget.Direct,
        browser: FabricShareBrowser,
    ) {
        guestLease.hold(target, browser)
    }

    @JvmStatic
    fun guestConnectionChanged(connected: Boolean) {
        guestLease.connectionChanged(connected)
    }

    @JvmStatic
    fun viewModel(): ShareViewModel =
        checkNotNull(installation).viewModel

    @JvmStatic
    fun friendCardIssuer(): FriendCardIssuer =
        checkNotNull(installation).friendCardIssuer

    @JvmStatic
    fun armFriendCardExchange(peerId: String) {
        friendCardConsent.arm(peerId)
    }

    @JvmStatic
    fun consumeFriendCardExchangeConsent(): FriendCardExchangeProof? =
        friendCardConsent.consume()

    @JvmStatic
    fun integratedWorldChanged(
        worldAvailable: Boolean,
        identity: Any?,
    ) {
        installation?.runtime?.integratedWorldChanged(worldAvailable, identity)
    }

    @JvmStatic
    fun shutdown() {
        friendCardConsent.cancel()
        guestLease.close()
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

internal class GuestConnectionLease(
    private val nowNanos: () -> Long = System::nanoTime,
    private val connectTimeoutNanos: Long = 60_000_000_000L,
) : AutoCloseable {
    private var active: Active? = null

    @Synchronized
    fun hold(
        connection: AutoCloseable,
        owner: AutoCloseable,
    ) {
        closeActive()
        active = Active(
            connection = connection,
            owner = owner,
            startedAtNanos = nowNanos(),
        )
    }

    @Synchronized
    fun connectionChanged(connected: Boolean) {
        val current = active ?: return
        if (connected) {
            current.connectionSeen = true
            return
        }
        val timedOut =
            nowNanos() - current.startedAtNanos >= connectTimeoutNanos
        if (current.connectionSeen || timedOut) {
            closeActive()
        }
    }

    @Synchronized
    override fun close() {
        closeActive()
    }

    private fun closeActive() {
        val current = active ?: return
        active = null
        closeBestEffort(current.connection)
        closeBestEffort(current.owner)
    }

    private fun closeBestEffort(resource: AutoCloseable) {
        try {
            resource.close()
        } catch (_: Exception) {
            // Closing a stale guest route must not prevent later joins.
        }
    }

    private data class Active(
        val connection: AutoCloseable,
        val owner: AutoCloseable,
        val startedAtNanos: Long,
        var connectionSeen: Boolean = false,
    )
}
