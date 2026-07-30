package com.minekube.connect.share.fabric

import com.google.inject.AbstractModule
import com.google.inject.Guice
import com.google.inject.multibindings.OptionalBinder
import com.google.inject.name.Names
import com.minekube.connect.ConnectPlatform
import com.minekube.connect.api.ConnectApi
import com.minekube.connect.api.logger.ConnectLogger
import com.minekube.connect.api.packet.PacketHandlers
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator
import com.minekube.connect.config.ConfigHolder
import com.minekube.connect.config.ConnectConfig
import com.minekube.connect.identity.EndpointTokenStore
import com.minekube.connect.inject.CommonPlatformInjector
import com.minekube.connect.module.Libp2pEndpointModule
import com.minekube.connect.module.ServerCommonModule
import com.minekube.connect.module.WatcherModule
import com.minekube.connect.platform.util.PlatformUtils
import com.minekube.connect.share.ConnectShareHandle
import com.minekube.connect.share.ConnectShareIngress
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.identity.EndpointIdentity
import com.minekube.connect.share.identity.EndpointIdentityStore
import com.minekube.connect.tunnel.p2p.Libp2pRuntime
import com.minekube.connect.watch.SessionAdmissionGate
import java.net.SocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope

class FabricConnectIngress private constructor(
    private val dataDirectory: Path,
    private val admission: AdmissionController,
    private val approvedJoins: ApprovedJoinTracker,
    private val scope: CoroutineScope,
    private val runtimeFactory: FabricConnectRuntimeFactory,
) : ConnectShareIngress {
    constructor(
        dataDirectory: Path,
        platformInjector: CommonPlatformInjector,
        logger: ConnectLogger,
        platformUtils: FabricPlatformUtils,
        admission: AdmissionController,
        approvedJoins: ApprovedJoinTracker,
        scope: CoroutineScope,
    ) : this(
        dataDirectory = dataDirectory,
        admission = admission,
        approvedJoins = approvedJoins,
        scope = scope,
        runtimeFactory = GuiceFabricConnectRuntimeFactory(
            dataDirectory = dataDirectory,
            platformInjector = platformInjector,
            logger = logger,
            platformUtils = platformUtils,
        ),
    )

    override suspend fun start(
        identity: EndpointIdentity,
        target: SocketAddress,
    ): ConnectShareHandle {
        val tokenFile = dataDirectory.resolve(EndpointIdentityStore.TOKEN_FILE_NAME)
        check(Files.isRegularFile(tokenFile)) {
            "Connect endpoint token must exist before sharing starts"
        }
        val persistedToken = EndpointTokenStore()
            .load(tokenFile, System.getenv())
            .orElseThrow {
                IllegalStateException("Connect endpoint token is missing")
            }
        check(persistedToken == identity.token) {
            "Connect endpoint identity changed before sharing started"
        }

        val gate = FabricSessionAdmissionGate(
            admission,
            scope,
            approvedJoins,
        )
        val runtime = try {
            runtimeFactory.start(identity, target, gate)
        } catch (failure: Throwable) {
            gate.stop()
            throw failure
        }
        val closed = AtomicBoolean()
        return ConnectShareHandle(
            endpoint = identity.endpoint,
            publicAddress = "${identity.endpoint}.play.minekube.net",
            close = {
                if (closed.compareAndSet(false, true)) {
                    gate.stop()
                    runtime.close()
                }
            },
        )
    }

    companion object {
        internal fun testing(
            dataDirectory: Path,
            admission: AdmissionController,
            scope: CoroutineScope,
            runtimeFactory: FabricConnectRuntimeFactory,
            approvedJoins: ApprovedJoinTracker =
                ApprovedJoinTracker(),
        ) = FabricConnectIngress(
            dataDirectory = dataDirectory,
            admission = admission,
            approvedJoins = approvedJoins,
            scope = scope,
            runtimeFactory = runtimeFactory,
        )
    }
}

fun interface FabricConnectRuntime {
    fun close()
}

fun interface FabricConnectRuntimeFactory {
    fun start(
        identity: EndpointIdentity,
        target: SocketAddress,
        admissionGate: SessionAdmissionGate,
    ): FabricConnectRuntime
}

private class GuiceFabricConnectRuntimeFactory(
    private val dataDirectory: Path,
    private val platformInjector: CommonPlatformInjector,
    private val logger: ConnectLogger,
    private val platformUtils: FabricPlatformUtils,
) : FabricConnectRuntimeFactory {
    override fun start(
        identity: EndpointIdentity,
        target: SocketAddress,
        admissionGate: SessionAdmissionGate,
    ): FabricConnectRuntime {
        check(platformInjector.serverSocketAddress == target) {
            "Minecraft bridge target changed before Connect started"
        }
        val injector = Guice.createInjector(
            ServerCommonModule(dataDirectory),
            FabricPlatformModule(
                platformInjector = platformInjector,
                logger = logger,
                platformUtils = platformUtils,
                admissionGate = admissionGate,
            ),
        )
        val platform = ConnectPlatform(
            injector.getInstance(ConnectApi::class.java),
            platformInjector,
            logger,
            injector,
            injector.getInstance(BedrockAdmissionCoordinator::class.java),
        )
        try {
            platform.initEmbedded(
                dataDirectory,
                ConnectConfig.embedded(identity.endpoint, true),
                injector.getInstance(ConfigHolder::class.java),
                injector.getInstance(PacketHandlers::class.java),
            )
            if (!platform.enable(
                Libp2pEndpointModule(),
                WatcherModule(),
            )) {
                throw IllegalStateException(
                    "Could not inject the Minecraft integrated server",
                )
            }
            return FabricConnectRuntime {
                try {
                    platform.disable()
                } finally {
                    Libp2pRuntime.close()
                }
            }
        } catch (failure: Throwable) {
            try {
                platform.disable()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            } finally {
                Libp2pRuntime.close()
            }
            throw failure
        }
    }
}

private class FabricPlatformModule(
    private val platformInjector: CommonPlatformInjector,
    private val logger: ConnectLogger,
    private val platformUtils: FabricPlatformUtils,
    private val admissionGate: SessionAdmissionGate,
) : AbstractModule() {
    override fun configure() {
        bind(CommonPlatformInjector::class.java).toInstance(platformInjector)
        bind(ConnectLogger::class.java).toInstance(logger)
        bind(PlatformUtils::class.java).toInstance(platformUtils)
        bindConstant()
            .annotatedWith(Names.named("platformName"))
            .to("Fabric")
        OptionalBinder.newOptionalBinder(
            binder(),
            SessionAdmissionGate::class.java,
        ).setBinding().toInstance(admissionGate)
    }
}
