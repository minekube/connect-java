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
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.network.netty.LocalSession;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.tunnel.Tunneler;
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
import minekube.connect.v1alpha1.ConnectLibp2P.OfflineMode;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerCapacity;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterResult;

public final class Libp2pEndpoint {
    static final String REGISTER_PROTOCOL_ID = "/minekube/connect/register/1.0.0";
    private static final long START_TIMEOUT_SECONDS = 10;
    private static final long CONNECT_TIMEOUT_SECONDS = 15;
    private static final long STREAM_TIMEOUT_SECONDS = 5;

    private final Path dataDirectory;
    private final ConnectConfig connectConfig;
    private final String connectToken;
    private final PlatformUtils platformUtils;
    private final ConnectLogger logger;
    private final PlatformInjector platformInjector;
    private final SimpleConnectApi api;
    private final String endpointInstanceId = newEndpointInstanceId();
    private final AtomicLong sequence = new AtomicLong();

    private Libp2pEndpointConfig libp2pConfig;
    private Host host;
    private EndpointPeerIdentity identity;
    private ScheduledExecutorService registrationExecutor;
    private ScheduledExecutorService statusExecutor;
    private ActiveRegistration activeRegistration;
    private List<String> reservedRelayAddrs = Collections.emptyList();
    private boolean started;

    @Inject
    public Libp2pEndpoint(
            @Named("dataDirectory") Path dataDirectory,
            ConnectConfig connectConfig,
            @Named("connectToken") String connectToken,
            PlatformUtils platformUtils,
            ConnectLogger logger,
            PlatformInjector platformInjector,
            SimpleConnectApi api) {
        this.dataDirectory = dataDirectory;
        this.connectConfig = connectConfig;
        this.connectToken = connectToken;
        this.platformUtils = platformUtils;
        this.logger = logger;
        this.platformInjector = platformInjector;
        this.api = api;
    }

    @Inject
    public synchronized void start() {
        libp2pConfig = Libp2pEndpointConfig.fromSystemEnvironment();
        if (!libp2pConfig.enabled()) {
            return;
        }
        try {
            identity = EndpointPeerIdentity.loadOrCreate(dataDirectory.resolve("libp2p-identity.key"));
            host = Libp2pTunnelTransport.createHost(
                    identity.privateKey(),
                    libp2pConfig.listenAddrs().toArray(String[]::new),
                    libp2pConfig.relayAddrs());
            installRegisterProtocol(host);
            Libp2pStatusReporter.installStatusProtocol(host);
            installSessionResponder(host);
            await(host.start(), START_TIMEOUT_SECONDS, "start libp2p endpoint host");
            reservedRelayAddrs = reserveRelayAddrs();
            started = true;
            PlatformUtils.AuthType authType = platformUtils.authType();
            OfflineMode offlineMode = offlineMode(authType);
            logger.info("Connect libp2p offline mode: "
                    + offlineMode
                    + " (allowOfflineModePlayers=" + connectConfig.getAllowOfflineModePlayers()
                    + ", authType=" + authType + ")");
            startRegistrationLoop(offlineMode);
        } catch (Exception e) {
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
                                proposal).connect());
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

    private ActiveRegistration registerOnce(OfflineMode offlineMode) {
        RuntimeException lastError = null;
        List<String> registerAddrs = libp2pConfig.registerAddrs();
        for (int i = 0; i < registerAddrs.size(); i++) {
            String address = registerAddrs.get(i);
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
                        Arrays.asList("session", "status"),
                        this::currentCapacity);
                client = new PeerRegistrationClient(handshake);
                PeerRegisterResult result = await(client.install(
                                stream,
                                observedAddrs(),
                                sequence.incrementAndGet(),
                                System.currentTimeMillis()),
                        CONNECT_TIMEOUT_SECONDS,
                        "register libp2p endpoint");
                return new ActiveRegistration(client, result);
            } catch (RuntimeException e) {
                if (client != null) {
                    client.close();
                }
                logRegisterAttemptFailure(address, i + 1, registerAddrs.size(), e);
                lastError = e;
            }
        }
        throw lastError == null
                ? new IllegalStateException("no libp2p Connect edge register addresses configured")
                : lastError;
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

    private void startRegistrationLoop(OfflineMode offlineMode) {
        registrationExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "connect-libp2p-registration");
            thread.setDaemon(true);
            return thread;
        });
        registerAndWatch(offlineMode);
    }

    private void scheduleRegisterRetry(OfflineMode offlineMode, long delaySeconds) {
        ScheduledExecutorService executor;
        synchronized (this) {
            if (!started || registrationExecutor == null || registrationExecutor.isShutdown()) {
                return;
            }
            executor = registrationExecutor;
        }
        executor.schedule(() -> {
            try {
                registerAndWatch(offlineMode);
            } catch (RuntimeException e) {
                if (Libp2pEndpointErrors.isTransientConnectError(e)) {
                    logger.warn("Connect libp2p registration refresh failed; retrying: "
                            + Libp2pEndpointErrors.summary(e));
                } else {
                    logger.error("Failed to refresh Connect libp2p registration", e);
                }
                scheduleRegisterRetry(offlineMode, 5);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void registerAndWatch(OfflineMode offlineMode) {
        ActiveRegistration registration = registerOnce(offlineMode);
        ActiveRegistration previous;
        synchronized (this) {
            if (!started) {
                registration.close();
                return;
            }
            previous = activeRegistration;
            activeRegistration = registration;
            logger.info("Connect libp2p endpoint registered: "
                    + registration.result().getEndpointId() + " (" + identity.peerId() + ")");
            startStatusReporter(registration.result());
        }
        if (previous != null) {
            previous.close();
        }
        registration.client().closedFuture().whenComplete((ignored, error) -> {
            if (error == null) {
                logger.info("Connect libp2p registration stream closed; reconnecting");
            } else if (Libp2pEndpointErrors.isTransientConnectError(error)) {
                logger.warn("Connect libp2p registration stream closed; reconnecting: "
                        + Libp2pEndpointErrors.summary(error));
            } else {
                logger.error("Connect libp2p registration stream failed; reconnecting", error);
            }
            scheduleRegisterRetry(offlineMode, 1);
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

    private List<String> observedAddrs() {
        return new ArrayList<>(reservedRelayAddrs);
    }

    private PeerCapacity currentCapacity() {
        return PeerCapacity.newBuilder()
                .setMaxSessions(512)
                .setActiveSessions(Math.max(0, platformUtils.getPlayerCount()))
                .build();
    }

    private List<String> reserveRelayAddrs() {
        List<String> addrs = new ArrayList<>();
        for (String relayAddr : libp2pConfig.relayAddrs()) {
            await(
                    host.getNetwork().listen(Multiaddr.fromString(relayAddr + "/p2p-circuit")),
                    CONNECT_TIMEOUT_SECONDS,
                    "reserve libp2p relay " + relayAddr);
            addrs.add(relayCircuitAddr(relayAddr, identity.peerId()));
        }
        return addrs;
    }

    static String relayCircuitAddr(String relayAddr, String endpointPeerId) {
        String separator = relayAddr.endsWith("/") ? "" : "/";
        return relayAddr + separator + "p2p-circuit/p2p/" + endpointPeerId;
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

    private void startStatusReporter(PeerRegisterResult result) {
        if (statusExecutor != null) {
            statusExecutor.shutdownNow();
            statusExecutor = null;
        }
        Libp2pStatusReporter reporter = new Libp2pStatusReporter(
                host,
                endpointInstanceId,
                identity.peerId(),
                libp2pConfig.registerAddrs(),
                result,
                platformUtils,
                logger);
        reporter.reportSafely();
        statusExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "connect-libp2p-status-report");
            thread.setDaemon(true);
            return thread;
        });
        statusExecutor.scheduleWithFixedDelay(
                reporter::reportSafely,
                Libp2pStatusReporter.REPORT_INTERVAL_SECONDS,
                Libp2pStatusReporter.REPORT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
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
