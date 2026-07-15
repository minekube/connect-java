/*
 * Copyright (c) 2021-2022 Minekube. https://minekube.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.tunnel.p2p;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockIdentityReadiness;
import com.minekube.connect.bedrock.BedrockIdentityReadiness.Transport;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.network.netty.LocalSession;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.p2p.impl.Libp2pTunnelTransportRuntime;
import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multistream.StrictProtocolBinding;
import io.libp2p.protocol.ProtocolHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import minekube.connect.v1alpha1.ConnectLibp2P.EndpointAuthType;
import minekube.connect.v1alpha1.ConnectLibp2P.OfflineMode;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerCapacity;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterResult;

final class Libp2pEndpointRuntime {
    static final String REGISTER_PROTOCOL_ID = "/minekube/connect/register/1.0.0";
    private static final long START_TIMEOUT_SECONDS = 10;
    private static final long CONNECT_TIMEOUT_SECONDS = 15;
    private static final long STREAM_TIMEOUT_SECONDS = 5;
    private static final int REGISTER_ATTEMPTS_PER_ADDRESS = 4;
    private static final int RELAY_RESERVATION_ATTEMPTS_PER_ADDRESS = 4;
    private static final int WATCHLESS_STATUS_FAILURE_THRESHOLD = 3;

    private final Path dataDirectory;
    private final ConnectConfig connectConfig;
    private final String connectToken;
    private final PlatformUtils platformUtils;
    private final ConnectLogger logger;
    private final PlatformInjector platformInjector;
    private final SimpleConnectApi api;
    private final BedrockIdentityReadiness bedrockIdentityReadiness;
    private final BedrockAdmissionCoordinator admissionCoordinator;
    private final String endpointInstanceId = newEndpointInstanceId();
    private final AtomicLong sequence = new AtomicLong();

    private Libp2pEndpointConfig libp2pConfig;
    private Host host;
    private EndpointPeerIdentity identity;
    private ScheduledExecutorService registrationExecutor;
    private ScheduledExecutorService statusExecutor;
    private ActiveRegistration activeRegistration;
    private Libp2pWatchlessController watchlessController;
    private List<String> reservedRelayAddrs = Collections.emptyList();
    private boolean started;

    @Inject
    Libp2pEndpointRuntime(
            @Named("dataDirectory") Path dataDirectory,
            ConnectConfig connectConfig,
            @Named("connectToken") String connectToken,
            PlatformUtils platformUtils,
            ConnectLogger logger,
            PlatformInjector platformInjector,
            SimpleConnectApi api,
            BedrockIdentityReadiness bedrockIdentityReadiness,
            BedrockAdmissionCoordinator admissionCoordinator) {
        this.dataDirectory = dataDirectory;
        this.connectConfig = connectConfig;
        this.connectToken = connectToken;
        this.platformUtils = platformUtils;
        this.logger = logger;
        this.platformInjector = platformInjector;
        this.api = api;
        this.bedrockIdentityReadiness = bedrockIdentityReadiness;
        this.admissionCoordinator = admissionCoordinator;
    }

    Libp2pEndpointRuntime(
            @Named("dataDirectory") Path dataDirectory,
            ConnectConfig connectConfig,
            @Named("connectToken") String connectToken,
            PlatformUtils platformUtils,
            ConnectLogger logger,
            PlatformInjector platformInjector,
            SimpleConnectApi api,
            BedrockIdentityReadiness bedrockIdentityReadiness) {
        this(dataDirectory, connectConfig, connectToken, platformUtils, logger, platformInjector, api,
                bedrockIdentityReadiness, null);
    }

    @Inject
    public synchronized void start() {
        libp2pConfig = Libp2pEndpointConfig.fromSystemEnvironment();
        startWithConfig(libp2pConfig);
    }

    synchronized void start(List<String> registerAddrs, List<String> relayAddrs, boolean watchless) {
        start(registerAddrs, relayAddrs, watchless, null, null);
    }

    synchronized void start(
            List<String> registerAddrs,
            List<String> relayAddrs,
            boolean watchless,
            Runnable watchlessReady,
            Runnable watchFallback) {
        if (started) {
            return;
        }
        Libp2pEndpointConfig environmentConfig = Libp2pEndpointConfig.fromSystemEnvironment();
        libp2pConfig = environmentConfig.enabled()
                ? environmentConfig
                : Libp2pEndpointConfig.fromBootstrap(registerAddrs, relayAddrs, watchless);
        startWithConfig(libp2pConfig, watchlessReady, watchFallback);
    }

    private void startWithConfig(Libp2pEndpointConfig config) {
        startWithConfig(config, null, null);
    }

    private void startWithConfig(Libp2pEndpointConfig config, Runnable watchlessReady, Runnable watchFallback) {
        if (started) {
            return;
        }
        libp2pConfig = Objects.requireNonNull(config, "config");
        if (!libp2pConfig.enabled()) {
            return;
        }
        watchlessController = new Libp2pWatchlessController(
                libp2pConfig.watchless(),
                WATCHLESS_STATUS_FAILURE_THRESHOLD,
                watchlessReady,
                watchFallback);
        try {
            identity = EndpointPeerIdentity.loadOrCreate(dataDirectory.resolve("libp2p-identity.key"));
            host = Libp2pTunnelTransportRuntime.createHost(
                    identity.privateKey(),
                    libp2pConfig.listenAddrs().toArray(String[]::new),
                    libp2pConfig.relayAddrs());
            installRegisterProtocol(host);
            Libp2pStatusReporter.installStatusProtocol(host);
            installSessionResponder(host);
            await(host.start(), START_TIMEOUT_SECONDS, "start libp2p endpoint host");
            started = true;
            PlatformUtils.AuthType authType = platformUtils.authType();
            OfflineMode offlineMode = offlineMode(authType);
            EndpointAuthType endpointAuthType = endpointAuthType(authType);
            logger.info("Connect libp2p offline mode: "
                    + offlineMode
                    + " (allowOfflineModePlayers=" + connectConfig.getAllowOfflineModePlayers()
                    + ", authType=" + authType + ")");
            startRegistrationLoop(offlineMode, endpointAuthType);
        } catch (Exception | LinkageError e) {
            stop();
            logger.error("Failed to start Connect libp2p endpoint", e);
        }
    }

    public synchronized void stop() {
        if (registrationExecutor != null) {
            registrationExecutor.shutdownNow();
            registrationExecutor = null;
        }
        if (activeRegistration != null) {
            activeRegistration.close();
            activeRegistration = null;
        }
        watchlessController = null;
        reservedRelayAddrs = Collections.emptyList();
        if (statusExecutor != null) {
            statusExecutor.shutdownNow();
            statusExecutor = null;
        }
        if (!started || host == null) {
            return;
        }
        Host stopping = host;
        host = null;
        started = false;
        await(stopping.stop(), START_TIMEOUT_SECONDS, "stop libp2p endpoint host");
    }

    private void installSessionResponder(Host host) {
        Libp2pSessionResponder responder = new Libp2pSessionResponder(
                connectConfig.getEndpoint(),
                System::currentTimeMillis,
                libp2pConfig.edgePeerIds(),
                (proposal, tunneler) ->
                        new LocalSession(
                                logger,
                                api,
                                tunneler,
                                platformInjector.getServerSocketAddress(),
                                proposal).connect(),
                admissionCoordinator);
        host.addProtocolHandler(new StrictProtocolBinding<Void>(
                Libp2pSessionResponder.PROTOCOL_ID,
                new ProtocolHandler<Void>(Long.MAX_VALUE, Long.MAX_VALUE) {
                    @Override
                    protected CompletableFuture<Void> onStartInitiator(Stream stream) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    protected CompletableFuture<Void> onStartResponder(Stream stream) {
                        responder.install(stream);
                        return CompletableFuture.completedFuture(null);
                    }
                }) {
        });
    }

    static void installRegisterProtocol(Host host) {
        host.addProtocolHandler(new StrictProtocolBinding<Void>(
                REGISTER_PROTOCOL_ID,
                new ProtocolHandler<Void>(Long.MAX_VALUE, Long.MAX_VALUE) {
                    @Override
                    protected CompletableFuture<Void> onStartInitiator(Stream stream) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    protected CompletableFuture<Void> onStartResponder(Stream stream) {
                        return CompletableFuture.completedFuture(null);
                    }
                }) {
        });
    }

    private ActiveRegistration registerOnce(OfflineMode offlineMode, EndpointAuthType authType) {
        RuntimeException lastError = null;
        List<String> attemptAddrs = registerAttemptAddresses(
                libp2pConfig.registerAddrs(),
                REGISTER_ATTEMPTS_PER_ADDRESS);
        for (int i = 0; i < attemptAddrs.size(); i++) {
            String address = attemptAddrs.get(i);
            PeerRegistrationClient client = null;
            try {
                Stream stream = openRegisterStream(address);
                PeerRegistrationHandshake handshake = new PeerRegistrationHandshake(
                        identity,
                        connectConfig.getEndpoint(),
                        connectToken,
                        endpointInstanceId,
                        connectConfig.getSuperEndpoints() == null
                                ? Collections.emptyList()
                                : connectConfig.getSuperEndpoints(),
                        offlineMode,
                        authType,
                        bedrockIdentityReadiness.capabilities(
                                libp2pConfig.capabilities(),
                                Transport.LIBP2P),
                        this::currentCapacity);
                client = new PeerRegistrationClient(handshake);
                PeerRegisterResult result = await(client.install(
                                stream,
                                this::refreshObservedAddrs,
                                sequence.incrementAndGet(),
                                System.currentTimeMillis()),
                        CONNECT_TIMEOUT_SECONDS,
                        "register libp2p endpoint");
                return new ActiveRegistration(client, result);
            } catch (RuntimeException e) {
                if (client != null) {
                    client.close();
                }
                logRegisterAttemptFailure(address, i + 1, attemptAddrs.size(), e);
                lastError = e;
            }
        }
        throw lastError == null
                ? new IllegalStateException("no libp2p Connect edge register addresses configured")
                : lastError;
    }

    static List<String> registerAttemptAddresses(List<String> registerAddrs, int attemptsPerAddress) {
        if (registerAddrs == null || registerAddrs.isEmpty()) {
            return Collections.emptyList();
        }
        if (attemptsPerAddress <= 1) {
            return registerAddrs;
        }
        List<String> attempts = new ArrayList<>(registerAddrs.size() * attemptsPerAddress);
        for (int round = 0; round < attemptsPerAddress; round++) {
            attempts.addAll(registerAddrs);
        }
        return attempts;
    }

    private void logRegisterAttemptFailure(String address, int attempt, int total, RuntimeException error) {
        String summary = Libp2pEndpointErrors.summary(error);
        String expectedPeer = edgePeerId(address);
        String message = "Connect libp2p registration attempt failed"
                + " attempt=" + attempt + "/" + total
                + " expectedEdgePeer=" + expectedPeer
                + " address=" + address
                + " error=" + summary;
        if (Libp2pEndpointErrors.isEdgePeerMismatch(error)) {
            message += " hint=fly-anycast-reached-different-edge-peer";
        }
        if (Libp2pEndpointErrors.isTransientConnectError(error)) {
            logger.warn(message);
        } else {
            logger.error(message, error);
        }
    }

    private static String edgePeerId(String address) {
        try {
            PeerId peerId = Multiaddr.fromString(address).getPeerId();
            return peerId == null ? "unknown" : peerId.toBase58();
        } catch (RuntimeException e) {
            return "invalid";
        }
    }

    private void startRegistrationLoop(OfflineMode offlineMode, EndpointAuthType authType) {
        registrationExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "connect-libp2p-registration");
            thread.setDaemon(true);
            return thread;
        });
        registerAndWatch(offlineMode, authType);
        registrationExecutor.scheduleWithFixedDelay(
                this::refreshRegistrationReadiness, 5, 5, TimeUnit.SECONDS);
    }

    private void refreshRegistrationReadiness() {
        if (!bedrockIdentityReadiness.refresh(Transport.LIBP2P)) {
            return;
        }
        ActiveRegistration registration;
        synchronized (this) {
            registration = activeRegistration;
        }
        if (registration != null) {
            registration.close();
        }
    }

    private void scheduleRegisterRetry(OfflineMode offlineMode, EndpointAuthType authType, long delaySeconds) {
        ScheduledExecutorService executor;
        synchronized (this) {
            if (!started || registrationExecutor == null || registrationExecutor.isShutdown()) {
                return;
            }
            executor = registrationExecutor;
        }
        executor.schedule(() -> {
            try {
                registerAndWatch(offlineMode, authType);
            } catch (RuntimeException e) {
                if (Libp2pEndpointErrors.isTransientConnectError(e)) {
                    logger.warn("Connect libp2p registration refresh failed; retrying: "
                            + Libp2pEndpointErrors.summary(e));
                } else {
                    logger.error("Failed to refresh Connect libp2p registration", e);
                }
                scheduleRegisterRetry(offlineMode, authType, 5);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void registerAndWatch(OfflineMode offlineMode, EndpointAuthType authType) {
        ActiveRegistration registration = registerOnce(offlineMode, authType);
        ActiveRegistration previous;
        Libp2pWatchlessController controller;
        boolean statusHealthy;
        synchronized (this) {
            if (!started) {
                registration.close();
                return;
            }
            previous = activeRegistration;
            activeRegistration = registration;
            logger.info("Connect libp2p endpoint registered: "
                    + registration.result().getEndpointId() + " (" + identity.peerId() + ")");
            statusHealthy = startStatusReporter(registration.result());
            controller = watchlessController;
        }
        if (previous != null) {
            previous.close();
        }
        if (controller != null) {
            controller.registrationCommitted(statusHealthy);
        }
        registration.client().closedFuture().whenComplete((ignored, error) -> {
            Libp2pWatchlessController currentController;
            synchronized (this) {
                if (activeRegistration != registration) {
                    return;
                }
                currentController = watchlessController;
                stopStatusReporter();
            }
            if (currentController != null) {
                currentController.registrationClosed();
            }
            if (error == null) {
                logger.info("Connect libp2p registration stream closed; reconnecting");
            } else if (Libp2pEndpointErrors.isTransientConnectError(error)) {
                logger.warn("Connect libp2p registration stream closed; reconnecting: "
                        + Libp2pEndpointErrors.summary(error));
            } else {
                logger.error("Connect libp2p registration stream failed; reconnecting", error);
            }
            scheduleRegisterRetry(offlineMode, authType, 1);
        });
    }

    private Stream openRegisterStream(String address) {
        Multiaddr multiaddr = Multiaddr.fromString(address);
        PeerId peerId = Objects.requireNonNull(multiaddr.getPeerId(),
                "libp2p Connect edge address must include /p2p/<peer-id>");
        Connection connection = await(
                host.getNetwork().connect(peerId, multiaddr),
                CONNECT_TIMEOUT_SECONDS,
                "connect libp2p Connect edge peer " + peerId);
        StreamPromise<Object> promise = host.newStream(Arrays.asList(REGISTER_PROTOCOL_ID), connection);
        Stream stream = await(promise.getStream(), STREAM_TIMEOUT_SECONDS, "open libp2p register stream");
        await(stream.getProtocol(), STREAM_TIMEOUT_SECONDS, "negotiate libp2p register protocol");
        return stream;
    }

    private List<String> refreshObservedAddrs() {
        List<String> addrs = reserveRelayAddrs();
        synchronized (this) {
            if (started) {
                reservedRelayAddrs = addrs;
            }
        }
        return new ArrayList<>(addrs);
    }

    private PeerCapacity currentCapacity() {
        return PeerCapacity.newBuilder()
                .setMaxSessions(512)
                .setActiveSessions(Math.max(0, platformUtils.getPlayerCount()))
                .build();
    }

    private List<String> reserveRelayAddrs() {
        return reserveRelayAddrs(
                libp2pConfig.relayAddrs(),
                identity.peerId(),
                relayAddr -> await(
                        host.getNetwork().listen(Multiaddr.fromString(relayAddr + "/p2p-circuit")),
                        CONNECT_TIMEOUT_SECONDS,
                        "reserve libp2p relay " + relayAddr),
                logger);
    }

    static List<String> reserveRelayAddrs(
            List<String> relayAddrs,
            String endpointPeerId,
            RelayReservation reservation,
            ConnectLogger logger) {
        List<String> reserved = new ArrayList<>();
        RuntimeException lastError = null;
        int total = relayAddrs.size() * RELAY_RESERVATION_ATTEMPTS_PER_ADDRESS;
        for (int i = 0; i < relayAddrs.size(); i++) {
            String relayAddr = relayAddrs.get(i);
            for (int attempt = 0; attempt < RELAY_RESERVATION_ATTEMPTS_PER_ADDRESS; attempt++) {
                try {
                    reservation.reserve(relayAddr);
                    reserved.add(relayCircuitAddr(relayAddr, endpointPeerId));
                    break;
                } catch (RuntimeException e) {
                    lastError = e;
                    logRelayReservationFailure(
                            logger,
                            relayAddr,
                            i * RELAY_RESERVATION_ATTEMPTS_PER_ADDRESS + attempt + 1,
                            total,
                            e);
                }
            }
        }
        if (reserved.isEmpty() && lastError != null) {
            throw new IllegalStateException("failed to reserve any configured libp2p relay", lastError);
        }
        return reserved;
    }

    private static void logRelayReservationFailure(
            ConnectLogger logger,
            String relayAddr,
            int attempt,
            int total,
            RuntimeException error) {
        String message = "Connect libp2p relay reservation failed"
                + " attempt=" + attempt + "/" + total
                + " expectedEdgePeer=" + edgePeerId(relayAddr)
                + " address=" + relayAddr
                + " error=" + Libp2pEndpointErrors.summary(error);
        if (Libp2pEndpointErrors.isEdgePeerMismatch(error)) {
            message += " hint=fly-anycast-reached-different-edge-peer";
        }
        if (Libp2pEndpointErrors.isTransientConnectError(error)) {
            logger.warn(message);
        } else {
            logger.error(message, error);
        }
    }

    static String relayCircuitAddr(String relayAddr, String endpointPeerId) {
        String separator = relayAddr.endsWith("/") ? "" : "/";
        return relayAddr + separator + "p2p-circuit/p2p/" + endpointPeerId;
    }

    interface RelayReservation {
        void reserve(String relayAddr);
    }

    static String newEndpointInstanceId() {
        return UUID.randomUUID().toString();
    }

    private OfflineMode offlineMode(PlatformUtils.AuthType authType) {
        if (connectConfig.getAllowOfflineModePlayers() != null) {
            return connectConfig.getAllowOfflineModePlayers()
                    ? OfflineMode.OFFLINE_MODE_ALLOWED
                    : OfflineMode.OFFLINE_MODE_DENIED;
        }
        return authType == PlatformUtils.AuthType.OFFLINE
                ? OfflineMode.OFFLINE_MODE_ALLOWED
                : OfflineMode.OFFLINE_MODE_DENIED;
    }

    private static EndpointAuthType endpointAuthType(PlatformUtils.AuthType authType) {
        switch (authType) {
            case ONLINE:
                return EndpointAuthType.ENDPOINT_AUTH_TYPE_ONLINE;
            case OFFLINE:
                return EndpointAuthType.ENDPOINT_AUTH_TYPE_OFFLINE;
            case PROXIED:
                return EndpointAuthType.ENDPOINT_AUTH_TYPE_PROXIED;
            default:
                return EndpointAuthType.ENDPOINT_AUTH_TYPE_UNSPECIFIED;
        }
    }

    private static <T> T await(CompletableFuture<T> future, long timeoutSeconds, String action) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed to " + action, e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("failed to " + action, e);
        } catch (Exception e) {
            throw new IllegalStateException("failed to " + action, e);
        }
    }

    private boolean startStatusReporter(PeerRegisterResult result) {
        stopStatusReporter();
        Libp2pStatusReporter reporter = new Libp2pStatusReporter(
                host,
                endpointInstanceId,
                identity.peerId(),
                libp2pConfig.registerAddrs(),
                result,
                platformUtils,
                logger);
        boolean initialStatusHealthy = reporter.reportSafely();
        statusExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "connect-libp2p-status-report");
            thread.setDaemon(true);
            return thread;
        });
        statusExecutor.scheduleWithFixedDelay(
                () -> {
                    boolean healthy = reporter.reportSafely();
                    Libp2pWatchlessController controller;
                    synchronized (this) {
                        controller = watchlessController;
                    }
                    if (controller != null) {
                        controller.statusReportResult(healthy);
                    }
                },
                Libp2pStatusReporter.REPORT_INTERVAL_SECONDS,
                Libp2pStatusReporter.REPORT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        return initialStatusHealthy;
    }

    private void stopStatusReporter() {
        if (statusExecutor != null) {
            statusExecutor.shutdownNow();
            statusExecutor = null;
        }
    }

    private static final class ActiveRegistration {
        private final PeerRegistrationClient client;
        private final PeerRegisterResult result;

        private ActiveRegistration(PeerRegistrationClient client, PeerRegisterResult result) {
            this.client = client;
            this.result = result;
        }

        private PeerRegistrationClient client() {
            return client;
        }

        private PeerRegisterResult result() {
            return result;
        }

        private void close() {
            client.close();
        }
    }
}
