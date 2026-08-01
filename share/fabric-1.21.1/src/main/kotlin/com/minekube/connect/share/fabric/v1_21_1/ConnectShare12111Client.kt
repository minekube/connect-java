package com.minekube.connect.share.fabric.v1_21_1

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
import com.minekube.connect.share.fabric.FollowAction
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.fabric.MinecraftStatusProbe
import com.minekube.connect.share.fabric.LoadedCompatibilityProfileFactory
import com.minekube.connect.share.fabric.LoadedMod
import com.minekube.connect.share.fabric.ModSide
import com.minekube.connect.share.ShareState
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.ModLoader
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.nio.file.Path
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
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component

class ConnectShare1211Runtime(
    private val platform: ConnectShare1211Platform,
) {
    fun initialize() {
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
        val activityIdentitySnapshot = AtomicReference<Any?>(null)
        val activityEpochSnapshot = AtomicReference<String?>(null)
        val joinTargetSnapshot = AtomicReference<String?>(null)
        val minecraftVersion =
            SharedConstants.getCurrentVersion().name
        val modVersion = platform.modVersion
        val compatibilityProfile = LoadedCompatibilityProfileFactory.create(
            minecraftVersion = minecraftVersion,
            loader = platform.loader,
            mods = platform.loadedMods,
            packEnvironment = System.getenv(),
        )
        val dataDirectory = platform.configDirectory
            .resolve("minekube-connect-share")
        val friendStore = FriendStore(dataDirectory)
        val browserReference =
            AtomicReference<FabricShareBrowser?>()
        val statusProbe = MinecraftStatusProbe()
        val remotePresence = FriendPresenceMonitor(
            store = friendStore,
            directProbe = { friend ->
                browserReference.get()?.probeLan(
                    friend = friend,
                    authMode = DirectP2pAuthMode.OFFLINE,
                    probe = statusProbe,
                )
            },
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
                    modVersion = modVersion,
                    worldAvailable = worldAvailableSnapshot.get(),
                    friendStore = friendStore,
                    playerCount = playerCountSnapshot::get,
                    worldDisplayName = worldNameSnapshot::get,
                    playerDisplayName = { client.user.name },
                    friendActivity = activitySnapshot::get,
                    compatibilityProfile = { compatibilityProfile },
                    friendJoinTarget = joinTargetSnapshot::get,
                    bridgeFactory = {
                            admission,
                            admissionScope,
                            approvedJoins,
                            gateway,
                        ->
                        GatewayMinecraft1211Bridge(gateway) {
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
                            client.setScreen(
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
                            client.setScreen(
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
                    platform.installFriendCardNetworking(
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

        platform.onEndClientTick { minecraft ->
            val installation =
                installationReference.get()
                    ?: return@onEndClientTick
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
            val activityIdentity: Any? = when {
                externalServer != null -> "server:${externalServer.ip}"
                worldAvailable -> server
                else -> null
            }
            if (activityIdentitySnapshot.getAndSet(activityIdentity) !=
                activityIdentity
            ) {
                activityEpochSnapshot.set(
                    activityIdentity?.let { UUID.randomUUID().toString() },
                )
            }
            activitySnapshot.set(
                FriendActivityResolver.resolve(
                    worldAvailable = worldAvailable,
                    worldSharingActive = installation.viewModel.state.value
                        .shareState is ShareState.Sharing,
                    worldName = worldNameSnapshot.get(),
                    externalServerName = externalServer?.name,
                    sessionEpoch = activityEpochSnapshot.get(),
                    compatibility = compatibilityProfile,
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
                    minecraft.toasts,
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
            processFollowActions(minecraft, installation, scope)
            socialNotifications.update(friends.state.value).forEach { event ->
                SystemToast.add(
                    minecraft.toasts,
                    SystemToast.SystemToastId(),
                    event.title(),
                    event.detail(),
                )
            }
        }
        platform.onClientStopping {
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

    private fun processFollowActions(
        minecraft: Minecraft,
        installation: ConnectShareInstallation,
        scope: CoroutineScope,
    ) {
        installation.friendsViewModel.followActions(
            activeGameplay = minecraft.level != null,
        ).forEach { action ->
            when (action) {
                is FollowAction.RequestJoin -> {
                    followToast(
                        minecraft,
                        "connect_share.notification.follow_waiting",
                        "connect_share.notification.follow_waiting_detail",
                        action.displayName,
                    )
                    scope.launch(Dispatchers.IO) {
                        installation.friendJoinOrchestrator.request(
                            action.peerId,
                            FriendJoinRequest(
                                requestId = UUID.randomUUID(),
                                playerName = minecraft.user.name,
                                playerUuid = minecraft.user.profileId,
                            ),
                        ).fold(
                            ifLeft = { failure ->
                                minecraft.execute {
                                    followToast(
                                        minecraft,
                                        "connect_share.notification.follow_failed",
                                        null,
                                        failure.safeMessage,
                                    )
                                }
                            },
                            ifRight = { target ->
                                minecraft.execute {
                                    if (minecraft.level != null) {
                                        target.close()
                                        followToast(
                                            minecraft,
                                            "connect_share.notification.follow_ready",
                                            "connect_share.notification.follow_ready_detail",
                                            action.displayName,
                                        )
                                    } else {
                                        connectFollow(
                                            minecraft,
                                            installation,
                                            action,
                                            target,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                is FollowAction.OfferJoinNow -> followToast(
                    minecraft,
                    "connect_share.notification.follow_ready",
                    "connect_share.notification.follow_ready_detail",
                    action.displayName,
                )

                is FollowAction.Expired -> followToast(
                    minecraft,
                    "connect_share.notification.follow_expired",
                    "connect_share.notification.follow_expired_detail",
                    action.displayName,
                )

                is FollowAction.Cancelled -> Unit
            }
        }
    }

    private fun connectFollow(
        minecraft: Minecraft,
        installation: ConnectShareInstallation,
        action: FollowAction.RequestJoin,
        target: GuestJoinTarget,
    ) {
        val address = when (target) {
            is GuestJoinTarget.Connect ->
                ServerAddress.parseString(target.publicAddress)
            is GuestJoinTarget.Direct -> ServerAddress(
                target.localAddress.hostString,
                target.localAddress.port,
            )
        }
        if (target is GuestJoinTarget.Direct) {
            ConnectShareClient.holdGuestDirect(target)
        }
        installation.friendsViewModel.completeFollow(action.peerId)
        ConnectScreen.startConnecting(
            minecraft.screen ?: TitleScreen(),
            minecraft,
            address,
            ServerData(
                action.displayName,
                address.toString(),
                ServerData.Type.OTHER,
            ),
            false,
            null,
        )
    }

    private fun followToast(
        minecraft: Minecraft,
        titleKey: String,
        detailKey: String?,
        value: String,
    ) {
        SystemToast.add(
            minecraft.toasts,
            SystemToast.SystemToastId(),
            Component.translatable(titleKey, value),
            detailKey?.let { Component.translatable(it, value) }
                ?: Component.literal(value),
        )
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

interface ConnectShare1211Platform {
    val modVersion: String
    val loader: ModLoader
    val loadedMods: List<LoadedMod>
    val configDirectory: Path

    fun onEndClientTick(callback: (Minecraft) -> Unit)

    fun onClientStopping(callback: () -> Unit)

    fun installFriendCardNetworking(
        scope: CoroutineScope,
        issuer: com.minekube.connect.share.fabric.FriendCardIssuer,
        receiver: FriendCardReceiver,
        approvedJoins: com.minekube.connect.share.fabric.ApprovedJoinTracker,
    )
}
