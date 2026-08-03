package com.minekube.connect.share.fabric

import com.minekube.connect.share.ShareState
import com.minekube.connect.share.ShareConnectionGateway
import com.minekube.connect.share.fabric.ui.ShareViewModel
import com.minekube.connect.share.fabric.ui.FriendsViewModel
import com.minekube.connect.share.fabric.ui.menuLabel
import com.minekube.connect.share.fabric.ui.overview
import com.minekube.connect.share.fabric.recovery.RecoveryViewModel

fun interface ConnectShareScreenFactory {
    fun open(parent: Any, active: Boolean)
}

fun interface ConnectShareGuestScreenFactory {
    fun open(
        parent: Any,
        browser: FabricShareBrowser,
        activity: FriendActivityMonitor,
    )
}

data class ConnectShareInstallation(
    val viewModel: ShareViewModel,
    val friendsViewModel: FriendsViewModel,
    val recoveryViewModel: RecoveryViewModel,
    val runtime: ConnectShareRuntime,
    val friendCardIssuer: FriendCardIssuer,
    val friendCardReceiver: FriendCardReceiver,
    val friendRequestClient: FriendRequestClient,
    val friendPairingClient: FriendPairingClient,
    val friendJoinOrchestrator: FriendJoinOrchestrator,
    val diagnostics: ShareJoinDiagnostics,
    val minecraftVersion: String,
    val modVersion: String,
    val approvedJoins: ApprovedJoinTracker,
    val controlPlane: ConnectControlPlane,
    val directControlPlane: DirectControlPlane,
    val browser: FabricShareBrowser,
    val friendActivity: FriendActivityMonitor,
    val gateway: ShareConnectionGateway,
    val ownConnectAddress: () -> String,
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
    fun friendsButtonTranslationKey(): String = installation
        ?.friendsViewModel
        ?.state
        ?.value
        ?.overview()
        ?.menuLabel()
        ?.translationKey
        ?: "connect_share.menu.join"

    @JvmStatic
    fun friendsButtonCount(): Int = installation
        ?.friendsViewModel
        ?.state
        ?.value
        ?.overview()
        ?.menuLabel()
        ?.count
        ?: 0

    @JvmStatic
    fun openPauseScreen(parent: Any) {
        installation?.let { installed ->
            installed.screens.open(parent, isShareActive())
        }
    }

    @JvmStatic
    fun openJoinScreen(parent: Any) {
        installation?.let { installed ->
            installed.guestScreens.open(
                parent,
                installed.browser,
                installed.friendActivity,
            )
        }
    }

    fun holdGuestDirect(
        target: GuestJoinTarget.Direct,
    ) {
        guestLease.hold(target, NOOP_CLOSE)
    }

    @JvmStatic
    fun guestConnectionChanged(connected: Boolean) {
        guestLease.connectionChanged(connected)
    }

    @JvmStatic
    fun viewModel(): ShareViewModel =
        checkNotNull(installation).viewModel

    @JvmStatic
    fun friendsViewModel(): FriendsViewModel =
        checkNotNull(installation).friendsViewModel

    @JvmStatic
    fun recoveryViewModel(): RecoveryViewModel =
        checkNotNull(installation).recoveryViewModel

    @JvmStatic
    fun friendCardIssuer(): FriendCardIssuer =
        checkNotNull(installation).friendCardIssuer

    @JvmStatic
    fun friendCardReceiver(): FriendCardReceiver =
        checkNotNull(installation).friendCardReceiver

    @JvmStatic
    fun friendRequestClient(): FriendRequestClient =
        checkNotNull(installation).friendRequestClient

    @JvmStatic
    fun friendPairingClient(): FriendPairingClient =
        checkNotNull(installation).friendPairingClient

    @JvmStatic
    fun friendJoinOrchestrator(): FriendJoinOrchestrator =
        checkNotNull(installation).friendJoinOrchestrator

    @JvmStatic
    fun diagnosticBundle(): String = checkNotNull(installation).let {
        it.diagnostics.bundle(it.minecraftVersion, it.modVersion)
    }

    @JvmStatic
    fun connectPublicAddress(): String? =
        installation?.ownConnectAddress?.invoke()

    @JvmStatic
    fun armFriendCardExchange(peerId: String) {
        installation?.friendsViewModel
            ?.relationshipId(peerId)
            ?.let { relationshipId ->
                friendCardConsent.arm(peerId, relationshipId)
            }
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
    suspend fun shutdown() {
        friendCardConsent.cancel()
        guestLease.close()
        installation?.let { installed ->
            installed.recoveryViewModel.close()
            installed.runtime.shutdown()
            installed.directControlPlane.shutdown()
            installed.controlPlane.shutdown()
            installed.browser.close()
            installed.gateway.close()
        }
        installation = null
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

    private val NOOP_CLOSE = AutoCloseable {}
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
