package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.admission.NewAdmissionTracker
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.ConnectShareInstallation
import com.minekube.connect.share.fabric.FabricLocalLoginAdmission
import com.minekube.connect.share.fabric.FabricLocalLoginAdmissionGate
import com.minekube.connect.share.fabric.FabricShareBootstrap
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.FriendCardReceiver
import com.minekube.connect.share.fabric.FriendActivityResolver
import com.minekube.connect.share.fabric.SocialEvent
import com.minekube.connect.share.fabric.SocialEventTracker
import com.minekube.connect.share.fabric.FriendPresenceMonitor
import com.minekube.connect.share.fabric.MinecraftStatusProbe
import com.minekube.connect.share.ShareState
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ConnectShare262Client : ClientModInitializer {
    override fun onInitializeClient() {
        val client = Minecraft.getInstance()
        val clientDispatcher = client.asCoroutineDispatcher()
        val scope = CoroutineScope(
            SupervisorJob() + clientDispatcher,
        )
        val installationReference =
            AtomicReference<ConnectShareInstallation?>()
        val worldAvailableSnapshot =
            AtomicBoolean(client.hasSingleplayerServer())
        val playerCountSnapshot = AtomicInteger(
            client.singleplayerServer?.playerList?.playerCount ?: 0,
        )
        val worldNameSnapshot = AtomicReference(
            client.singleplayerServer?.worldData?.levelName
                ?: "Minecraft world",
        )
        val activitySnapshot = AtomicReference(
            FriendActivity(FriendActivityKind.ONLINE),
        )
        val joinTargetSnapshot = AtomicReference<String?>(null)
        val minecraftVersion =
            SharedConstants.getCurrentVersion().name()
        val dataDirectory = FabricLoader.getInstance().configDir
            .resolve("minekube-connect-share")
        val friendStore = FriendStore(dataDirectory)
        val browserReference =
            AtomicReference<FabricShareBrowser?>()
        val statusProbe = MinecraftStatusProbe()
        val remotePresence = FriendPresenceMonitor(
            store = friendStore,
            probe = statusProbe,
            directProbe = { friend ->
                browserReference.get()?.probeLan(
                    friend = friend,
                    authMode = DirectP2pAuthMode.OFFLINE,
                    probe = statusProbe,
                )
            },
            ownConnectAddress =
                ConnectShareClient::connectPublicAddress,
        )
        scope.launch {
            while (isActive) {
                remotePresence.refresh()
                delay(PRESENCE_REFRESH_MILLIS)
            }
        }
        val bootstrapJob = scope.launch(Dispatchers.IO) {
            try {
                val installation = FabricShareBootstrap.create(
                    scope = scope,
                    dataDirectory = dataDirectory,
                    minecraftVersion = minecraftVersion,
                    worldAvailable = worldAvailableSnapshot.get(),
                    friendStore = friendStore,
                    playerCount = playerCountSnapshot::get,
                    worldDisplayName = worldNameSnapshot::get,
                    playerDisplayName = { client.user.name },
                    friendActivity = activitySnapshot::get,
                    friendJoinTarget = joinTargetSnapshot::get,
                    bridgeFactory = {
                            admission,
                            admissionScope,
                            approvedJoins,
                            gateway,
                        ->
                        GatewayMinecraft262Bridge(gateway) {
                            FabricLocalLoginAdmissionGate(
                                admission = FabricLocalLoginAdmission(
                                    admission,
                                    approvedJoins,
                                ),
                                scope = admissionScope,
                            )
                        }
                    },
                    screens = { parent, active ->
                        val parentScreen = parent as Screen
                        client.execute {
                            client.gui.setScreen(
                                if (active) {
                                    ShareStatusScreen(parentScreen)
                                } else {
                                    ShareSetupScreen(parentScreen)
                                },
                            )
                        }
                    },
                    guestScreens = { parent, browser, activity ->
                        val parentScreen = parent as Screen
                        client.execute {
                            client.gui.setScreen(
                                ShareJoinScreen(
                                    parent = parentScreen,
                                    friends =
                                        ConnectShareClient.friendsViewModel(),
                                    browser = browser,
                                    remotePresence = remotePresence,
                                    friendActivity = activity,
                                ),
                            )
                        }
                    },
                )
                browserReference.set(installation.browser)
                withContext(clientDispatcher) {
                    FriendCardNetworking.install(
                        scope = scope,
                        issuer = installation.friendCardIssuer,
                        receiver = installation.friendCardReceiver,
                        approvedJoins = installation.approvedJoins,
                    )
                    ConnectShareClient.install(installation)
                    installationReference.set(installation)
                    LOGGER.info(
                        "Connect Share friend gateway is ready",
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                LOGGER.log(
                    Level.SEVERE,
                    "Connect Share initialization failed",
                    failure,
                )
            }
        }
        val admissionNotifications = NewAdmissionTracker()
        val socialNotifications = SocialEventTracker()
        val admissionToastId = SystemToast.SystemToastId()

        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            val installation =
                installationReference.get()
                    ?: return@register
            val server = minecraft.singleplayerServer
            val worldAvailable = server != null && minecraft.connection != null
            worldAvailableSnapshot.set(worldAvailable)
            playerCountSnapshot.set(
                server?.playerList?.playerCount ?: 0,
            )
            worldNameSnapshot.set(
                server?.worldData?.levelName ?: "Minecraft world",
            )
            val currentServer = minecraft.currentServer
            val externalServer = currentServer
                ?.takeIf { !worldAvailable }
            joinTargetSnapshot.set(externalServer?.ip)
            activitySnapshot.set(
                FriendActivityResolver.resolve(
                    worldAvailable = worldAvailable,
                    worldSharingActive = installation.viewModel.state.value
                        .shareState is ShareState.Sharing,
                    worldName = worldNameSnapshot.get(),
                    externalServerName = externalServer?.name,
                ),
            )
            ConnectShareClient.integratedWorldChanged(
                worldAvailable,
                server,
            )
            ConnectShareClient.guestConnectionChanged(
                minecraft.connection != null,
            )
            admissionNotifications.update(
                installation.viewModel.state.value.pendingAdmissions,
            ).firstOrNull()?.let { request ->
                SystemToast.add(
                    minecraft.gui.toastManager(),
                    admissionToastId,
                    Component.translatable(
                        if (request.purpose == AdmissionPurpose.FRIEND) {
                            "connect_share.notification.friend_request"
                        } else {
                            "connect_share.notification.join_request"
                        },
                    ),
                    Component.translatable(
                        if (request.purpose == AdmissionPurpose.FRIEND) {
                            "connect_share.notification.friend_request_detail"
                        } else {
                            "connect_share.notification.join_request_detail"
                        },
                        request.identity.name,
                    ),
                )
            }
            val friends = installation.friendsViewModel
            friends.updateIncoming(
                installation.viewModel.state.value.pendingAdmissions,
            )
            friends.updateRemotePresence(remotePresence.state.value)
            friends.updateActivities(installation.friendActivity.state.value)
            socialNotifications.update(friends.state.value).forEach { event ->
                SystemToast.add(
                    minecraft.gui.toastManager(),
                    SystemToast.SystemToastId(),
                    event.title(),
                    event.detail(),
                )
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            scope.launch(Dispatchers.IO) {
                bootstrapJob.cancelAndJoin()
                try {
                    if (installationReference.get() != null) {
                        ConnectShareClient.shutdown()
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    private companion object {
        const val PRESENCE_REFRESH_MILLIS = 10_000L
        val LOGGER: Logger = Logger.getLogger("Connect")
    }

    private fun SocialEvent.title(): Component = Component.translatable(
        when (this) {
            is SocialEvent.FriendAccepted ->
                "connect_share.notification.friend_accepted"
            is SocialEvent.FriendRemoved ->
                "connect_share.notification.friend_removed"
            is SocialEvent.PlayingServer ->
                "connect_share.notification.friend_playing"
            is SocialEvent.WorldReady ->
                "connect_share.notification.friend_online"
        },
    )

    private fun SocialEvent.detail(): Component = when (this) {
        is SocialEvent.FriendAccepted -> Component.translatable(
            "connect_share.notification.friend_accepted_detail",
            displayName,
        )
        is SocialEvent.FriendRemoved -> Component.translatable(
            "connect_share.notification.friend_removed_detail",
            displayName,
        )
        is SocialEvent.PlayingServer -> Component.translatable(
            "connect_share.notification.friend_playing_detail",
            displayName,
            serverName,
        )
        is SocialEvent.WorldReady -> Component.translatable(
            "connect_share.notification.friend_online_detail",
            displayName,
            worldName ?: "Minecraft world",
        )
    }
}
