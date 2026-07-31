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
import com.minekube.connect.share.fabric.FriendOnlineTracker
import com.minekube.connect.share.fabric.FriendPresenceMonitor
import com.minekube.connect.share.fabric.MinecraftStatusProbe
import com.minekube.connect.share.friend.FriendStore
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
        val minecraftVersion =
            SharedConstants.getCurrentVersion().name()
        val minecraftProtocolVersion =
            SharedConstants.getProtocolVersion()
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
                    minecraftProtocolVersion = minecraftProtocolVersion,
                    worldAvailable = worldAvailableSnapshot.get(),
                    friendStore = friendStore,
                    playerCount = playerCountSnapshot::get,
                    worldDisplayName = worldNameSnapshot::get,
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
                    guestScreens = { parent, browser ->
                        val parentScreen = parent as Screen
                        client.execute {
                            client.gui.setScreen(
                                ShareJoinScreen(
                                    parent = parentScreen,
                                    friends =
                                        ConnectShareClient.friendsViewModel(),
                                    browser = browser,
                                    remotePresence = remotePresence,
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
        val friendNotifications = FriendOnlineTracker()
        val admissionToastId = SystemToast.SystemToastId()
        val friendToastId = SystemToast.SystemToastId()

        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            val installation =
                installationReference.get()
                    ?: return@register
            val server = minecraft.singleplayerServer
            val worldAvailable = minecraft.hasSingleplayerServer()
            worldAvailableSnapshot.set(worldAvailable)
            playerCountSnapshot.set(
                server?.playerList?.playerCount ?: 0,
            )
            worldNameSnapshot.set(
                server?.worldData?.levelName ?: "Minecraft world",
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
            friendNotifications.update(
                remotePresence.state.value,
            ).firstOrNull()?.let { friend ->
                SystemToast.add(
                    minecraft.gui.toastManager(),
                    friendToastId,
                    Component.translatable(
                        "connect_share.notification.friend_online",
                    ),
                    Component.translatable(
                        "connect_share.notification.friend_online_detail",
                        friend.displayName,
                    ),
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
        const val PRESENCE_REFRESH_MILLIS = 30_000L
        val LOGGER: Logger = Logger.getLogger("Connect")
    }
}
