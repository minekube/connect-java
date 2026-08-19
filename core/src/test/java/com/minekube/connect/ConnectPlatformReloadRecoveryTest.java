package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.bedrock.BedrockIdentityReadiness;
import com.minekube.connect.bedrock.BedrockPrincipalReadiness;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.inject.CommonPlatformInjector;
import com.minekube.connect.module.WatcherModule;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import com.minekube.connect.util.Metrics;
import com.minekube.connect.util.UpdateChecker;
import com.minekube.connect.watch.SessionProposal;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression guard: a plugin reload (disable → enable on the same platform instance) must not leave
 * the parent-scoped {@link BedrockAdmissionCoordinator} closed, otherwise every session proposal
 * throws {@code IllegalStateException: ... coordinator is closed} and the watch dies.
 *
 * <p>{@code ConnectPlatform.disable()} closes the coordinator (a parent-scoped singleton shared by
 * every enable() cycle), while {@code WatcherRegister}/{@code WatchClient} are recreated per enable
 * in a child injector. {@code ConnectPlatform.enable()} must recover the coordinator before the new
 * cycle's watcher binds, or the first proposal after a reload kills the WebSocket and the watch
 * reconnects forever (public report: "Connection error with WatchService: ... coordinator is
 * closed").
 */
class ConnectPlatformReloadRecoveryTest {

    static {
        // bstats MetricsBase refuses to construct when its classes are not shaded/relocated
        // (release-jar-only). The documented test escape hatch; the test config disables metrics
        // anyway, so Metrics stays inert.
        System.setProperty("bstats.relocatecheck", "false");
    }

    /** The enable() wiring alone: a coordinator closed by the previous disable() is usable again. */
    @Test
    void enableRecoversCoordinatorClosedByPreviousDisable() throws Exception {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        PlatformInjector platformInjector = mock(PlatformInjector.class);
        when(platformInjector.inject()).thenReturn(true);
        Injector guice = mock(Injector.class);
        when(guice.createChildInjector(any(Module[].class))).thenReturn(guice);
        when(guice.getInstance(UpdateChecker.class)).thenReturn(mock(UpdateChecker.class));
        ConnectPlatform platform = new ConnectPlatform(
                mock(ConnectApi.class), platformInjector, mock(ConnectLogger.class), guice, coordinator);
        setConfig(platform, new ConnectConfig());

        coordinator.close(); // simulate the previous disable() cycle
        assertTrue(platform.enable());

        // The shared coordinator must accept proposals again; a closed one throws ISE here.
        Session session = session("session-recovered");
        SessionProposal proposal = assertDoesNotThrow(
                () -> coordinator.proposal(session, reason -> { }, "", ""));
        coordinator.discard(proposal);
    }

    /**
     * End-to-end reload cycle: enable → deliver proposal (join accepted) → disable (watcher stopped,
     * coordinator closed) → enable with a new child injector → deliver proposal again. The second
     * proposal must be accepted, not fail with the "coordinator is closed" ISE that kills the watch.
     */
    @Test
    void reloadCycleDeliversSessionProposalThroughRecoveredCoordinator() throws Exception {
        ConnectConfig config = new ConnectConfig();
        disableMetrics(config);
        OkHttpClient watchHttpClient = mock(OkHttpClient.class);
        PlatformInjector platformInjector = mock(PlatformInjector.class);
        when(platformInjector.inject()).thenReturn(true);
        when(platformInjector.getServerSocketAddress())
                .thenReturn(new InetSocketAddress("127.0.0.1", 25565));
        Tunneler tunneler = mock(Tunneler.class);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        ConnectLogger logger = mock(ConnectLogger.class);

        Injector parent = Guice.createInjector(testModule(
                config, watchHttpClient, platformInjector, tunneler, coordinator, logger));
        ConnectPlatform platform = new ConnectPlatform(
                mock(ConnectApi.class), platformInjector, logger, parent, coordinator);
        setConfig(platform, config);

        // Cycle 1: enable, watch connects, proposal is accepted.
        platform.enable(new WatcherModule());
        WebSocketListener listener1 = captureListener(watchHttpClient, 1);
        Session session1 = session("session-1");
        listener1.onMessage(mock(WebSocket.class), bytes(session1));
        verify(tunneler).prepare(session1);

        // Reload teardown: watcher stopped, parent coordinator closed.
        platform.disable();

        // Cycle 2: the reload re-instantiates the platform on the SAME parent injector (new child
        // injector, same parent coordinator singleton, which disable() closed).
        ConnectPlatform reloaded = new ConnectPlatform(
                mock(ConnectApi.class), platformInjector, logger, parent, coordinator);
        setConfig(reloaded, config);
        reloaded.enable(new WatcherModule());
        WebSocketListener listener2 = captureListener(watchHttpClient, 2);
        Session session2 = session("session-2");
        // RED before the fix: proposal() throws ISE (closed coordinator) and the watch would die.
        assertDoesNotThrow(
                () -> listener2.onMessage(mock(WebSocket.class), bytes(session2)),
                "a proposal after reload must be accepted, not fail with 'coordinator is closed'");
        verify(tunneler, times(2)).prepare(any(Session.class));

        reloaded.disable(); // stop the cycle-2 watcher's scheduler and coordinator executor
    }

    private static Module testModule(
            ConnectConfig config,
            OkHttpClient watchHttpClient,
            PlatformInjector platformInjector,
            Tunneler tunneler,
            BedrockAdmissionCoordinator coordinator,
            ConnectLogger logger) {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(ConnectConfig.class).toInstance(config);
                bind(ConnectApi.class).toInstance(mock(ConnectApi.class));
                bind(SimpleConnectApi.class).toInstance(new SimpleConnectApi(logger));
                bind(PlatformInjector.class).toInstance(platformInjector);
                bind(ConnectLogger.class).toInstance(logger);
                bind(OkHttpClient.class)
                        .annotatedWith(Names.named("watchHttpClient"))
                        .toInstance(watchHttpClient);
                bind(BedrockIdentityReadiness.class).toInstance(new BedrockIdentityReadiness(
                        config, new BedrockIdentityKeyProvider(config, new OkHttpClient())));
                bind(BedrockPrincipalReadiness.class).toInstance(new BedrockPrincipalReadiness(config));
                bind(BedrockAdmissionCoordinator.class).toInstance(coordinator);
                bind(PlatformUtils.class).toInstance(mock(PlatformUtils.class));
                bind(String.class)
                        .annotatedWith(Names.named("platformName"))
                        .toInstance("test");
                bind(UpdateChecker.class).toInstance(mock(UpdateChecker.class));
                bind(Libp2pEndpoint.class).toInstance(mock(Libp2pEndpoint.class));
                bind(Tunneler.class).toInstance(tunneler);
                bind(CommonPlatformInjector.class).toInstance(mock(CommonPlatformInjector.class));
            }
        };
    }

    private static WebSocketListener captureListener(OkHttpClient httpClient, int invocation)
            throws Exception {
        ArgumentCaptor<WebSocketListener> listener = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(httpClient, times(invocation)).newWebSocket(any(Request.class), listener.capture());
        return listener.getValue();
    }

    private static ByteString bytes(Session session) {
        return ByteString.of(WatchResponse.newBuilder().setSession(session).build().toByteArray());
    }

    private static Session session(String id) {
        return Session.newBuilder()
                .setId(id)
                .setTunnelServiceAddr("wss://tunnel.example")
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(GameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000001")
                                .setName("Player")))
                .build();
    }

    private static void setConfig(ConnectPlatform platform, ConnectConfig config) throws Exception {
        Field field = ConnectPlatform.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(platform, config);
    }

    /** bstats must stay inert in tests: it would otherwise start a daemon scheduler thread. */
    private static void disableMetrics(ConnectConfig config) throws Exception {
        Field metricsField = ConnectConfig.class.getDeclaredField("metrics");
        metricsField.setAccessible(true);
        Object metrics = metricsField.get(config);
        if (metrics == null) {
            metrics = new ConnectConfig.MetricsConfig();
            metricsField.set(config, metrics);
        }
        Field disabled = metrics.getClass().getDeclaredField("disabled");
        disabled.setAccessible(true);
        disabled.set(metrics, true);
    }
}
