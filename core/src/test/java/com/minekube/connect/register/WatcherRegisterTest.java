package com.minekube.connect.register;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.bedrock.BedrockIdentityReadiness;
import com.minekube.connect.bedrock.BedrockIdentityReadiness.Transport;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.watch.SessionProposal;
import com.minekube.connect.watch.WatchBootstrap;
import com.minekube.connect.watch.WatchClient;
import com.minekube.connect.watch.Watcher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.ArgumentCaptor;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfileProperty;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport.Type;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class WatcherRegisterTest {
    private WatcherRegister register;

    @AfterEach
    void stopRegister() {
        if (register != null) {
            register.stop();
        }
    }

    @Test
    void constructorDoesNotCreateScheduler() throws Exception {
        WatcherRegister register = new WatcherRegister();

        assertNull(scheduler(register));
    }

    @Test
    void startCreatesScheduler() throws Exception {
        register = newRegister();

        register.start();

        ScheduledExecutorService scheduler = scheduler(register);
        assertNotNull(scheduler);
        assertFalse(scheduler.isShutdown());
    }

    @Test
    void stopShutsDownSchedulerAndClearsField() throws Exception {
        register = newRegister();
        register.start();
        ScheduledExecutorService scheduler = scheduler(register);

        register.stop();

        assertNull(scheduler(register));
        assertTrue(scheduler.isShutdown());
    }

    @Test
    void startAfterStopCreatesNewScheduler() throws Exception {
        register = newRegister();
        register.start();
        ScheduledExecutorService firstScheduler = scheduler(register);

        register.stop();
        register.start();

        ScheduledExecutorService secondScheduler = scheduler(register);
        assertNotNull(secondScheduler);
        assertNotSame(firstScheduler, secondScheduler);
        assertTrue(firstScheduler.isShutdown());
        assertFalse(secondScheduler.isShutdown());
    }

    @Test
    void readinessTransitionReconnectsWatchAndWithdrawsThePreviousRegistration() throws Exception {
        Fixture fixture = newFixture();
        ConnectConfig config = identityConfig();
        BedrockIdentityReadiness readiness = new BedrockIdentityReadiness(
                config,
                new BedrockIdentityKeyProvider(config, new OkHttpClient()));
        readiness.observe(Transport.WATCH);
        inject(fixture.register, "bedrockIdentityReadiness", readiness);
        WebSocket webSocket = mock(WebSocket.class);
        when(fixture.watchClient.watch(any(Watcher.class))).thenReturn(webSocket);
        register = fixture.register;
        register.start();

        invokeRefreshWatchReadiness(register);
        verify(fixture.watchClient).watch(any(Watcher.class));

        setField(config.getBedrockIdentity(), "enforcement", "disabled");
        invokeRefreshWatchReadiness(register);

        verify(fixture.watchClient, times(2)).watch(any(Watcher.class));
        verify(webSocket).close(1000, "watcher is reconnecting");
    }

    @Test
    void stopBeforeStartDoesNotCreateScheduler() throws Exception {
        WatcherRegister register = new WatcherRegister();

        assertDoesNotThrow(register::stop);

        assertNull(scheduler(register));
    }

    @Test
    void retryDoesNotThrowWhenSchedulerIsClearedAfterStop() throws Exception {
        register = newRegister();
        register.start();
        register.stop();

        // Force the race window: started=true but scheduler already cleared.
        started(register).set(true);

        assertDoesNotThrow(() -> invokeRetry(register));
        assertNull(scheduler(register));
    }

    @Test
    void resetBackOffTimerDoesNotThrowWhenSchedulerIsClearedAfterStop() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());

        register.stop();

        assertDoesNotThrow(() -> watcher.getValue().onOpen(emptyBootstrap()));
        assertNull(scheduler(register));
    }

    @Test
    void watchOpenStartsLibp2pEndpointFromBootstrap() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());

        watcher.getValue().onOpen(WatchBootstrap.fromLists(
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("session", "status", "watchless")));

        verify(fixture.libp2pEndpoint).start(
                eq(List.of("/dns4/connect.example/tcp/4001/p2p/edge")),
                eq(List.of("/dns4/connect.example/tcp/4001/p2p/edge")),
                eq(true),
                any(Runnable.class),
                any(Runnable.class));
    }

    @Test
    void watchlessReadyClosesLegacyWatchSocketAndSuppressesReconnect() throws Exception {
        Fixture fixture = newFixture();
        RunnableCaptor callbacks = new RunnableCaptor();
        doAnswer(callbacks.capture()).when(fixture.libp2pEndpoint).start(
                any(),
                any(),
                anyBoolean(),
                any(Runnable.class),
                any(Runnable.class));
        WebSocket webSocket = mock(WebSocket.class);
        when(fixture.watchClient.watch(any(Watcher.class))).thenReturn(webSocket);
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());
        watcher.getValue().onOpen(WatchBootstrap.fromLists(
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("session", "status", "watchless")));
        assertTrue(register.isHealthy());
        callbacks.ready.run();
        watcher.getValue().onCompleted();

        assertTrue(register.isHealthy());
        verify(webSocket).close(1000, "watchless endpoint mode enabled");
        verifyNoMoreInteractions(fixture.watchClient);
    }

    @Test
    void watchlessReadyRestoresHealthAfterTerminalEventWinsRace() throws Exception {
        Fixture fixture = newFixture();
        RunnableCaptor callbacks = new RunnableCaptor();
        doAnswer(callbacks.capture()).when(fixture.libp2pEndpoint).start(
                any(),
                any(),
                anyBoolean(),
                any(Runnable.class),
                any(Runnable.class));
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());
        watcher.getValue().onOpen(WatchBootstrap.fromLists(
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("session", "status", "watchless")));

        watcher.getValue().onCompleted();
        callbacks.ready.run();

        assertTrue(register.isHealthy());
    }

    @Test
    void watchlessFallbackReopensLegacyWatchSocket() throws Exception {
        Fixture fixture = newFixture();
        RunnableCaptor callbacks = new RunnableCaptor();
        doAnswer(callbacks.capture()).when(fixture.libp2pEndpoint).start(
                any(),
                any(),
                anyBoolean(),
                any(Runnable.class),
                any(Runnable.class));
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());

        watcher.getValue().onOpen(WatchBootstrap.fromLists(
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("session", "status", "watchless")));
        callbacks.ready.run();
        callbacks.fallback.run();

        verify(fixture.watchClient, times(2)).watch(any(Watcher.class));
    }

    @Test
    void oldWatchBootstrapKeepsLegacyWatchSocketPrimary() throws Exception {
        Fixture fixture = newFixture();
        WebSocket webSocket = mock(WebSocket.class);
        when(fixture.watchClient.watch(any(Watcher.class))).thenReturn(webSocket);
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());

        watcher.getValue().onOpen(WatchBootstrap.fromLists(
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("session", "status")));

        verify(fixture.libp2pEndpoint).start(
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                false);
        verifyNoMoreInteractions(webSocket);
    }

    @Test
    void watcherHealthIsUnhealthyUntilWatchSocketOpens() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;

        assertFalse(register.isHealthy());

        register.start();

        assertFalse(register.isHealthy());

        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());
        watcher.getValue().onOpen(emptyBootstrap());

        assertTrue(register.isHealthy());
    }

    @Test
    void watcherHealthBecomesUnhealthyWhenWatchEnds() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());
        watcher.getValue().onOpen(emptyBootstrap());

        watcher.getValue().onCompleted();

        assertFalse(register.isHealthy());
    }

    @Test
    void watcherHealthBecomesUnhealthyWhenWatchErrors() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());
        watcher.getValue().onOpen(emptyBootstrap());

        watcher.getValue().onError(new RuntimeException("watch failed"));

        assertFalse(register.isHealthy());
    }

    @Test
    void watcherHealthBecomesUnhealthyWhenStopped() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());
        watcher.getValue().onOpen(emptyBootstrap());

        register.stop();

        assertFalse(register.isHealthy());
    }

    @Test
    void lateOpenAfterStopDoesNotMarkWatcherHealthy() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());

        register.stop();
        watcher.getValue().onOpen(emptyBootstrap());

        assertFalse(register.isHealthy());
    }

    @Test
    void watcherRegisterDoesNotDeclareTimerFields() {
        assertNoTimerFields(WatcherRegister.class);
        for (Class<?> nestedClass : WatcherRegister.class.getDeclaredClasses()) {
            assertNoTimerFields(nestedClass);
        }
    }

    @Test
    void acceptsLibp2pOnlyProposalWithoutLegacyTunnelServiceAddr() throws Exception {
        Fixture fixture = newFixture();
        register = fixture.register;
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());

        Session session = Session.newBuilder()
                .setId("session-libp2p")
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(GameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000001")
                                .setName("Player")
                                .build())
                        .build())
                .addTunnelTransports(TunnelTransport.newBuilder()
                        .setType(Type.TYPE_LIBP2P)
                        .setAddress("/ip4/127.0.0.1/tcp/1/p2p/test")
                        .build())
                .build();
        SessionProposal proposal = new SessionProposal(session, reason -> {
            throw new AssertionError("proposal should not be rejected: " + reason);
        });

        watcher.getValue().onProposal(proposal);

        verify(fixture.tunneler).prepare(session);
        verify(fixture.platformInjector).getServerSocketAddress();
    }

    @Test
    void invalidWatchProposalCancelsPrivateAdmissionImmediately() throws Exception {
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry());
        try {
            Fixture fixture = newFixture(coordinator);
            register = fixture.register;
            register.start();
            ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
            verify(fixture.watchClient).watch(watcher.capture());
            Session session = Session.newBuilder()
                    .setId("session-1")
                    .setPlayer(Player.newBuilder()
                            .setAddr("127.0.0.1")
                            .setProfile(GameProfile.newBuilder()
                                    .setId("00000000-0000-0000-0000-000000000001")
                                    .setName("Player")
                                    .addProperties(GameProfileProperty.newBuilder()
                                            .setName("minekube:bedrock_identity")
                                            .setValue("signed-envelope"))))
                    .build();
            SessionProposal proposal = coordinator.proposal(session, reason -> {}, "", "");

            watcher.getValue().onProposal(proposal);

            Field admissions = BedrockAdmissionCoordinator.class.getDeclaredField("admissions");
            admissions.setAccessible(true);
            assertTrue(((Map<?, ?>) admissions.get(coordinator)).isEmpty());
        } finally {
            coordinator.close();
        }
    }

    private static WatcherRegister newRegister() throws Exception {
        return newFixture().register;
    }

    private static WatchBootstrap emptyBootstrap() {
        return WatchBootstrap.fromLists(List.of(), List.of(), List.of());
    }

    private static Fixture newFixture() throws Exception {
        return newFixture(null);
    }

    private static Fixture newFixture(BedrockAdmissionCoordinator admissionCoordinator) throws Exception {
        WatcherRegister register = new WatcherRegister();
        WatchClient watchClient = mock(WatchClient.class);
        when(watchClient.watch(any(Watcher.class))).thenReturn(mock(WebSocket.class));

        inject(register, "watchClient", watchClient);
        inject(register, "tunneler", mock(Tunneler.class));
        inject(register, "platformInjector", mock(PlatformInjector.class));
        inject(register, "logger", mock(ConnectLogger.class));
        inject(register, "api", new SimpleConnectApi(mock(ConnectLogger.class)));
        inject(register, "libp2pEndpoint", mock(Libp2pEndpoint.class));
        if (admissionCoordinator != null) {
            inject(register, "admissionCoordinator", admissionCoordinator);
        }
        when(((PlatformInjector) getField(register, "platformInjector")).getServerSocketAddress())
                .thenReturn(new InetSocketAddress("127.0.0.1", 25565));
        return new Fixture(register, watchClient, (Tunneler) getField(register, "tunneler"),
                (PlatformInjector) getField(register, "platformInjector"),
                (Libp2pEndpoint) getField(register, "libp2pEndpoint"));
    }

    private static void inject(WatcherRegister register, String fieldName, Object value)
            throws Exception {
        Field field = WatcherRegister.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(register, value);
    }

    private static ConnectConfig identityConfig() throws Exception {
        ConnectConfig config = new ConnectConfig();
        setField(config.getBedrockIdentity(), "enforcement", "require");
        setField(config.getBedrockIdentity(), "publicKey",
                Base64.getEncoder().encodeToString(new byte[32]));
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ScheduledExecutorService scheduler(WatcherRegister register) throws Exception {
        return (ScheduledExecutorService) getField(register, "scheduler");
    }

    private static AtomicBoolean started(WatcherRegister register) throws Exception {
        return (AtomicBoolean) getField(register, "started");
    }

    private static Object getField(WatcherRegister register, String fieldName) throws Exception {
        Field field = WatcherRegister.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(register);
    }

    private static void invokeRetry(WatcherRegister register) throws Exception {
        Method method = WatcherRegister.class.getDeclaredMethod("retry");
        method.setAccessible(true);
        method.invoke(register);
    }

    private static void invokeRefreshWatchReadiness(WatcherRegister register) throws Exception {
        Method method = WatcherRegister.class.getDeclaredMethod("refreshWatchReadiness");
        method.setAccessible(true);
        method.invoke(register);
    }

    private static void assertNoTimerFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            assertFalse(Timer.class.isAssignableFrom(field.getType()),
                    type.getName() + "#" + field.getName() + " must not use java.util.Timer");
        }
    }

    private static final class Fixture {
        private final WatcherRegister register;
        private final WatchClient watchClient;
        private final Tunneler tunneler;
        private final PlatformInjector platformInjector;
        private final Libp2pEndpoint libp2pEndpoint;

        private Fixture(
                WatcherRegister register,
                WatchClient watchClient,
                Tunneler tunneler,
                PlatformInjector platformInjector,
                Libp2pEndpoint libp2pEndpoint
        ) {
            this.register = register;
            this.watchClient = watchClient;
            this.tunneler = tunneler;
            this.platformInjector = platformInjector;
            this.libp2pEndpoint = libp2pEndpoint;
        }
    }

    private static final class RunnableCaptor {
        private Runnable ready;
        private Runnable fallback;

        private Answer<Void> capture() {
            return invocation -> {
                ready = invocation.getArgument(3);
                fallback = invocation.getArgument(4);
                return null;
            };
        }
    }
}
