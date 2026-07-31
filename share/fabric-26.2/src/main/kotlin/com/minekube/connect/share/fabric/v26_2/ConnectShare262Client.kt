package com.minekube.connect.share.fabric.v26_2

import com.minekube.connect.share.admission.NewAdmissionTracker
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FabricLocalLoginAdmission
import com.minekube.connect.share.fabric.FabricLocalLoginAdmissionGate
import com.minekube.connect.share.fabric.FabricShareBootstrap
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.FriendCardReceiver
import com.minekube.connect.share.fabric.FriendOnlineTracker
import com.minekube.connect.share.fabric.FriendPresenceMonitor
import com.minekube.connect.share.fabric.ui.FriendsViewModel
import com.minekube.connect.share.friend.FriendStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        val scope = CoroutineScope(
            SupervisorJob() + client.asCoroutineDispatcher(),
        )
        val dataDirectory = FabricLoader.getInstance().configDir
            .resolve("minekube-connect-share")
        val friendStore = FriendStore(dataDirectory)
        val remotePresence = FriendPresenceMonitor(friendStore)
        scope.launch {
            while (isActive) {
                remotePresence.refresh()
                delay(PRESENCE_REFRESH_MILLIS)
            }
        }
        val installation = FabricShareBootstrap.create(
            scope = scope,
            dataDirectory = dataDirectory,
            minecraftVersion = SharedConstants.getCurrentVersion().name(),
            minecraftProtocolVersion = SharedConstants.getProtocolVersion(),
            worldAvailable = client.hasSingleplayerServer(),
            friendStore = friendStore,
            playerCount = {
                client.singleplayerServer?.playerList?.playerCount ?: 0
            },
            worldDisplayName = {
                client.singleplayerServer?.worldData?.levelName
                    ?: "Minecraft world"
            },
            bridgeFactory = { admission, admissionScope, approvedJoins ->
                Minecraft262Bridge {
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
            guestScreens = { parent ->
                val parentScreen = parent as Screen
                client.execute {
                    client.gui.setScreen(
                        ShareJoinScreen(
                            parent = parentScreen,
                            friends = FriendsViewModel(
                                friendStore,
                            ),
                            browser = FabricShareBrowser(dataDirectory),
                            remotePresence = remotePresence,
                        ),
                    )
                }
            },
        )
        FriendCardNetworking.install(
            scope = scope,
            issuer = installation.friendCardIssuer,
            receiver = installation.friendCardReceiver,
            approvedJoins = installation.approvedJoins,
        )
        ConnectShareClient.install(installation)
        val admissionNotifications = NewAdmissionTracker()
        val friendNotifications = FriendOnlineTracker()
        val admissionToastId = SystemToast.SystemToastId()
        val friendToastId = SystemToast.SystemToastId()

        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            ConnectShareClient.integratedWorldChanged(
                minecraft.hasSingleplayerServer(),
                minecraft.singleplayerServer,
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
            ConnectShareClient.shutdown()
            scope.cancel()
        }
    }

    private companion object {
        const val PRESENCE_REFRESH_MILLIS = 30_000L
    }
}
