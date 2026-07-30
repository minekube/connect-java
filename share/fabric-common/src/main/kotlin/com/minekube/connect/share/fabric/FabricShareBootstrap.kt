package com.minekube.connect.share.fabric

import com.minekube.connect.api.logger.ConnectLogger
import com.minekube.connect.identity.EndpointTokenStore
import com.minekube.connect.share.ShareCoordinator
import com.minekube.connect.share.VersionedMinecraftBridge
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.fabric.ui.ShareViewModel
import com.minekube.connect.share.fabric.ui.StoredEndpointIdentityUiActions
import com.minekube.connect.share.identity.EndpointIdentityStore
import com.minekube.connect.util.MessageFormatter
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

object FabricShareBootstrap {
    fun create(
        scope: CoroutineScope,
        dataDirectory: Path,
        minecraftVersion: String,
        worldAvailable: Boolean,
        playerCount: () -> Int,
        bridgeFactory:
            (AdmissionController, CoroutineScope) -> VersionedMinecraftBridge,
        screens: ConnectShareScreenFactory,
        environment: Map<String, String> = System.getenv(),
        logger: ConnectLogger = FabricConnectLogger(),
        httpClient: OkHttpClient = OkHttpClient(),
    ): ConnectShareInstallation {
        val viewModelReference = AtomicReference<ShareViewModel?>()
        val admission = AdmissionController(
            scope = scope,
            connectedCount = {
                (playerCount() - HOST_PLAYER_COUNT).coerceAtLeast(0)
            },
            maxGuests = {
                viewModelReference.get()?.state?.value?.options?.maxGuests
                    ?: DEFAULT_MAX_GUESTS
            },
        )
        val bridge = bridgeFactory(admission, scope)
        val identityStore = EndpointIdentityStore(
            directory = dataDirectory,
            environment = environment,
            endpointNames = RandomEndpointNameSource(httpClient),
            tokenStore = EndpointTokenStore(),
        )
        val validator = WatchEndpointCredentialValidator(
            client = httpClient,
            watchUrl = watchHttpUrl(environment),
            timeout = 10.seconds,
        )
        val ingress = FabricConnectIngress(
            dataDirectory = dataDirectory,
            platformInjector = bridge,
            logger = logger,
            platformUtils = FabricPlatformUtils(
                minecraftVersion = minecraftVersion,
                playerCount = playerCount,
            ),
            admission = admission,
            scope = scope,
        )
        val coordinator = ShareCoordinator(
            bridge = bridge,
            ingress = ingress,
            identityProvider = identityStore::currentOrCreate,
            admission = admission,
            failureReporter = logger::warn,
        )
        val viewModel = ShareViewModel(
            scope = scope,
            shareState = coordinator.state,
            pendingAdmissions = admission.pending,
            initialWorldAvailable = worldAvailable,
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
            worldAvailabilityChanged = viewModel::setWorldAvailable,
        )
        return ConnectShareInstallation(
            viewModel = viewModel,
            runtime = runtime,
            screens = screens,
        )
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
