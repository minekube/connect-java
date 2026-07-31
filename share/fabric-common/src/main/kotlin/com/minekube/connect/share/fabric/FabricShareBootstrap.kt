package com.minekube.connect.share.fabric

import com.minekube.connect.api.logger.ConnectLogger
import com.minekube.connect.identity.EndpointTokenStore
import com.minekube.connect.share.ShareCoordinator
import com.minekube.connect.share.ShareConnectionGateway
import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.VersionedMinecraftBridge
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.fabric.ui.ShareViewModel
import com.minekube.connect.share.fabric.ui.FriendsViewModel
import com.minekube.connect.share.fabric.ui.StoredEndpointIdentityUiActions
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendActivityRequest
import com.minekube.connect.share.friend.SharePreferences
import com.minekube.connect.share.friend.SharePreferencesStore
import com.minekube.connect.share.identity.EndpointIdentityStore
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.util.MessageFormatter
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

object FabricShareBootstrap {
    suspend fun create(
        scope: CoroutineScope,
        dataDirectory: Path,
        minecraftVersion: String,
        worldAvailable: Boolean,
        friendStore: FriendStore,
        playerCount: () -> Int,
        worldDisplayName: () -> String = { "Minecraft world" },
        playerDisplayName: () -> String? = { null },
        friendActivity: () -> FriendActivity = {
            FriendActivity(FriendActivityKind.ONLINE)
        },
        friendJoinTarget: () -> String? = { null },
        bridgeFactory:
            (
                AdmissionController,
                CoroutineScope,
                ApprovedJoinTracker,
                ShareConnectionGateway,
            ) -> VersionedMinecraftBridge,
        screens: ConnectShareScreenFactory,
        guestScreens: ConnectShareGuestScreenFactory,
        environment: Map<String, String> = System.getenv(),
        logger: ConnectLogger = FabricConnectLogger(),
        httpClient: OkHttpClient = OkHttpClient(),
    ): ConnectShareInstallation {
        val viewModelReference = AtomicReference<ShareViewModel?>()
        val approvedJoins = ApprovedJoinTracker()
        val admission = AdmissionController(
            scope = scope,
            connectedCount = {
                (playerCount() - HOST_PLAYER_COUNT).coerceAtLeast(0)
            },
            maxGuests = {
                viewModelReference.get()?.state?.value?.options?.maxGuests
                    ?: DEFAULT_MAX_GUESTS
            },
            autoApprove = { identity ->
                runCatching {
                    friendStore.all().any { friend ->
                        val directIdentityMatches =
                            friend.peerId == identity.directPeerId
                        val minecraftIdentityMatches =
                            identity is AdmissionIdentity.Authenticated &&
                                friend.minecraftUuid == identity.uuid
                        friend.permissions.canJoinAutomatically &&
                            (directIdentityMatches ||
                                minecraftIdentityMatches)
                    }
                }.getOrDefault(false)
            },
        )
        val identityStore = EndpointIdentityStore(
            directory = dataDirectory,
            environment = environment,
            endpointNames = RandomEndpointNameSource(httpClient),
            tokenStore = EndpointTokenStore(),
        )
        val preferencesStore = SharePreferencesStore(dataDirectory)
        val initialPreferences = try {
            preferencesStore.load()
        } catch (_: Exception) {
            logger.warn("Connect Share preferences could not be loaded")
            SharePreferences()
        }
        val validator = WatchEndpointCredentialValidator(
            client = httpClient,
            watchUrl = watchHttpUrl(environment),
            timeout = 10.seconds,
        )
        val endpointIdentity = identityStore.currentOrCreate()
        val ownConnectAddress =
            "${endpointIdentity.endpoint}.play.minekube.net"
        val friendCardIssuer = FriendCardIssuer(
            dataDirectory = dataDirectory,
            displayName = playerDisplayName,
            connectAddress = { ownConnectAddress },
        )
        val friendCardReceiver = FriendCardReceiver(friendStore)
        val friendRequestServer = FriendRequestServer(
            scope = scope,
            admission = admission,
            issuer = friendCardIssuer,
            receiver = friendCardReceiver,
            friendStore = friendStore,
            activity = friendActivity,
            joinTarget = friendJoinTarget,
        )
        val gateway = ShareConnectionGateway.bind(friendRequestServer)
        var browser: FabricShareBrowser? = null
        try {
            val directPeer = FabricDirectPeerRuntime(
                dataDirectory = dataDirectory,
                displayName = worldDisplayName,
            )
            val activeBrowser = directPeer.browser
            browser = activeBrowser
            activeBrowser.start().leftOrNull()?.let {
                logger.warn(it.safeMessage)
            }
            val bridge = bridgeFactory(
                admission,
                scope,
                approvedJoins,
                gateway,
            )
            val ingress = PersistentConnectIngress(
                FabricConnectIngress(
                    dataDirectory = dataDirectory,
                    platformInjector = gateway,
                    logger = logger,
                    platformUtils = FabricPlatformUtils(
                        minecraftVersion = minecraftVersion,
                        playerCount = playerCount,
                    ),
                    admission = admission,
                    approvedJoins = approvedJoins,
                    scope = scope,
                    worldAvailable = bridge::isInjected,
                ),
            )
            val directIngress = PersistentDirectIngress(
                directPeer.ingress,
            )
            val coordinator = ShareCoordinator(
                bridge = bridge,
                ingress = ingress,
                identityProvider = identityStore::currentOrCreate,
                admission = admission,
                directIngress = directIngress,
                failureReporter = logger::warn,
            )
            val viewModel = ShareViewModel(
                scope = scope,
                shareState = coordinator.state,
                pendingAdmissions = admission.pending,
                initialWorldAvailable = worldAvailable,
                initialShareWithFriendsEnabled =
                    initialPreferences.shareWithFriends,
                persistShareWithFriendsEnabled = { enabled ->
                    preferencesStore.save(
                        SharePreferences(shareWithFriends = enabled),
                    )
                },
                identityActions = StoredEndpointIdentityUiActions(
                    store = identityStore,
                    validator = validator,
                ),
                startShare = coordinator::start,
                stopShare = coordinator::stop,
                answerAdmission = admission::answer,
            )
            viewModelReference.set(viewModel)
            val runtime = ConnectShareRuntime(
                scope = scope,
                stopShare = {
                    coordinator.worldReplaced()
                },
                resumeShare = viewModel::resumeIfEnabled,
                worldAvailabilityChanged = viewModel::setWorldAvailable,
            )
            val friendRequestClient = FriendRequestClient()
            val removalSync = FriendRemovalSync(friendStore) { removal ->
                activeBrowser.openFriendControl(
                    friend = removal.friend,
                    authMode = DirectP2pAuthMode.OFFLINE,
                ).fold(
                    ifLeft = {
                        arrow.core.Either.Left(
                            FriendRequestFailure.Unreachable,
                        )
                    },
                    ifRight = { target ->
                        friendRequestClient.remove(
                            target,
                            com.minekube.connect.share.friend
                                .FriendRemovalRequest(removal.operationId),
                        )
                    },
                )
            }
            val friendsViewModel = FriendsViewModel(friendStore) {
                scope.launch(Dispatchers.IO) {
                    removalSync.sync()
                }
            }
            val activityMonitor = FriendActivityMonitor(
                store = friendStore,
                query = { friend ->
                activeBrowser.openFriendControl(
                    friend = friend,
                    authMode = DirectP2pAuthMode.OFFLINE,
                ).fold(
                    ifLeft = {
                        arrow.core.Either.Left(
                            FriendRequestFailure.Unreachable,
                        )
                    },
                    ifRight = { target ->
                        friendRequestClient.activity(
                            target,
                            FriendActivityRequest(UUID.randomUUID()),
                        )
                    },
                )
                },
            )
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    activityMonitor.refresh()
                    delay(ACTIVITY_REFRESH_MILLIS)
                }
            }
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    removalSync.sync()
                    delay(REMOVAL_SYNC_MILLIS)
                }
            }
            val friendPairingClient = FriendPairingClient(
                store = friendStore,
                issuer = friendCardIssuer,
                receiver = friendCardReceiver,
                requestClient = friendRequestClient,
            )
            val controlPlane = ConnectControlPlane(
                scope = scope,
                ingress = ingress,
                identity = { endpointIdentity },
                target = gateway.serverSocketAddress,
                failureReporter = logger::warn,
            ).also(ConnectControlPlane::start)
            val directControlPlane = DirectControlPlane(
                scope = scope,
                ingress = directIngress,
                options = ShareOptions(
                    gameMode = ShareGameMode.SURVIVAL,
                    allowCheats = false,
                    allowInternetDirect = false,
                ),
                target = gateway.directAddress,
                connectAddress = { ownConnectAddress },
                failureReporter = logger::warn,
            ).also(DirectControlPlane::start)
            return ConnectShareInstallation(
                viewModel = viewModel,
                friendsViewModel = friendsViewModel,
                runtime = runtime,
                friendCardIssuer = friendCardIssuer,
                friendCardReceiver = friendCardReceiver,
                friendRequestClient = friendRequestClient,
                friendPairingClient = friendPairingClient,
                approvedJoins = approvedJoins,
                controlPlane = controlPlane,
                directControlPlane = directControlPlane,
                browser = activeBrowser,
                friendActivity = activityMonitor,
                gateway = gateway,
                ownConnectAddress = ownConnectAddress,
                screens = screens,
                guestScreens = guestScreens,
            )
        } catch (failure: Throwable) {
            browser?.close()
            gateway.close()
            throw failure
        }
    }

    internal fun watchHttpUrl(environment: Map<String, String>) =
        normalizeWebSocketScheme(
            environment[WATCH_URL_ENV] ?: DEFAULT_WATCH_URL,
        ).toHttpUrlOrNull()
            ?: normalizeWebSocketScheme(DEFAULT_WATCH_URL).toHttpUrl()

    private fun normalizeWebSocketScheme(value: String): String = when {
        value.startsWith("wss://", ignoreCase = true) ->
            "https://${value.substring(WSS_SCHEME_LENGTH)}"

        value.startsWith("ws://", ignoreCase = true) ->
            "http://${value.substring(WS_SCHEME_LENGTH)}"

        else -> value
    }

    private const val WATCH_URL_ENV = "CONNECT_WATCH_URL"
    private const val DEFAULT_WATCH_URL = "wss://watch-connect.minekube.net"
    private const val WSS_SCHEME_LENGTH = 6
    private const val WS_SCHEME_LENGTH = 5
    private const val HOST_PLAYER_COUNT = 1
    private const val DEFAULT_MAX_GUESTS = 8
    private const val REMOVAL_SYNC_MILLIS = 10_000L
    private const val ACTIVITY_REFRESH_MILLIS = 10_000L
}

private class FabricConnectLogger(
    private val delegate: Logger = Logger.getLogger(ConnectLogger.LOGGER_NAME),
) : ConnectLogger {
    @Volatile
    private var debugEnabled = false

    override fun error(message: String, vararg args: Any?) {
        delegate.severe(MessageFormatter.format(message, *args))
    }

    override fun error(
        message: String,
        throwable: Throwable,
        vararg args: Any?,
    ) {
        delegate.log(
            Level.SEVERE,
            MessageFormatter.format(message, *args),
            throwable,
        )
    }

    override fun warn(message: String, vararg args: Any?) {
        delegate.warning(MessageFormatter.format(message, *args))
    }

    override fun info(message: String, vararg args: Any?) {
        delegate.info(MessageFormatter.format(message, *args))
    }

    override fun translatedInfo(message: String, vararg args: Any?) {
        info(message, *args)
    }

    override fun debug(message: String, vararg args: Any?) {
        if (debugEnabled) {
            delegate.fine(MessageFormatter.format(message, *args))
        }
    }

    override fun trace(message: String, vararg args: Any?) {
        if (debugEnabled) {
            delegate.finer(MessageFormatter.format(message, *args))
        }
    }

    override fun enableDebug() {
        debugEnabled = true
    }

    override fun disableDebug() {
        debugEnabled = false
    }

    override fun isDebug(): Boolean = debugEnabled
}
