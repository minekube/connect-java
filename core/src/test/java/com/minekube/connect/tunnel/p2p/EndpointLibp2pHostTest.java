package com.minekube.connect.tunnel.p2p;

import com.minekube.connect.tunnel.p2p.impl.Libp2pTunnelTransportRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.Stream;
import io.libp2p.core.StreamPromise;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndpointLibp2pHostTest {

    @TempDir
    Path tempDir;

    @Test
    void createsHostWithPersistedEndpointIdentity() throws Exception {
        EndpointPeerIdentity identity = EndpointPeerIdentity.loadOrCreate(tempDir.resolve("libp2p-identity.key"));

        Host host = Libp2pTunnelTransportRuntime.createHost(identity.privateKey());
        try {
            assertEquals(identity.peerId(), host.getPeerId().toString());
            host.start().get(10, TimeUnit.SECONDS);
        } finally {
            host.stop().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void endpointHostCanOpenRegisterProtocolStream() throws Exception {
        endpointHostCanOpenProtocolStream(Libp2pEndpoint.REGISTER_PROTOCOL_ID, host ->
                Libp2pEndpointRuntime.installRegisterProtocol(host));
    }

    @Test
    void endpointHostCanOpenStatusProtocolStream() throws Exception {
        endpointHostCanOpenProtocolStream(Libp2pStatusReporter.PROTOCOL_ID, host ->
                Libp2pStatusReporter.installStatusProtocol(host));
    }

    @Test
    void endpointHostCanReserveRelayListenAddress() throws Exception {
        Host relay = Libp2pTunnelTransportRuntime.createRelayServiceHost(
                EndpointPeerIdentity.loadOrCreate(tempDir.resolve("relay.key")).privateKey(),
                "/ip4/127.0.0.1/tcp/0");
        Host endpoint = null;
        Host edge = null;
        CountDownLatch accepted = new CountDownLatch(1);
        try {
            relay.start().get(10, TimeUnit.SECONDS);
            String relayAddress = relay.listenAddresses().get(0).withP2P(relay.getPeerId()).toString();
            endpoint = Libp2pTunnelTransportRuntime.createHost(
                    EndpointPeerIdentity.loadOrCreate(tempDir.resolve("endpoint-relay.key")).privateKey(),
                    new String[]{"/ip4/127.0.0.1/tcp/0"},
                    Collections.singletonList(relayAddress));
            Libp2pEndpointRuntime.installRegisterProtocol(endpoint);
            endpoint.addProtocolHandler(new io.libp2p.core.multistream.StrictProtocolBinding<Void>(
                    "test-relay-stream",
                    new io.libp2p.protocol.ProtocolHandler<Void>(Long.MAX_VALUE, Long.MAX_VALUE) {
                        @Override
                        protected CompletableFuture<Void> onStartInitiator(Stream stream) {
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        protected CompletableFuture<Void> onStartResponder(Stream stream) {
                            accepted.countDown();
                            return CompletableFuture.completedFuture(null);
                        }
                    }) {
            });
            endpoint.start().get(10, TimeUnit.SECONDS);

            endpoint.getNetwork()
                    .listen(io.libp2p.core.multiformats.Multiaddr.fromString(relayAddress + "/p2p-circuit"))
                    .get(10, TimeUnit.SECONDS);
            edge = Libp2pTunnelTransportRuntime.createHost(
                    EndpointPeerIdentity.loadOrCreate(tempDir.resolve("edge-relay.key")).privateKey(),
                    new String[]{"/ip4/127.0.0.1/tcp/0"},
                    Collections.singletonList(relayAddress));
            edge.addProtocolHandler(new io.libp2p.core.multistream.StrictProtocolBinding<Void>(
                    "test-relay-stream",
                    new io.libp2p.protocol.ProtocolHandler<Void>(Long.MAX_VALUE, Long.MAX_VALUE) {
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
            edge.start().get(10, TimeUnit.SECONDS);
            String endpointRelayAddress = relayAddress + "/p2p-circuit/p2p/" + endpoint.getPeerId().toBase58();
            Connection connection = edge.getNetwork()
                    .connect(PeerId.fromBase58(endpoint.getPeerId().toBase58()), io.libp2p.core.multiformats.Multiaddr.fromString(endpointRelayAddress))
                    .get(10, TimeUnit.SECONDS);
            StreamPromise<Object> promise = edge.newStream(
                    Collections.singletonList("test-relay-stream"),
                    connection);
            Stream stream = promise.getStream().get(10, TimeUnit.SECONDS);
            stream.getProtocol().get(10, TimeUnit.SECONDS);

            assertTrue(accepted.await(5, TimeUnit.SECONDS), "relay circuit stream was not accepted");
        } finally {
            if (edge != null) {
                edge.stop().get(10, TimeUnit.SECONDS);
            }
            if (endpoint != null) {
                endpoint.stop().get(10, TimeUnit.SECONDS);
            }
            relay.stop().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void directEndpointHostDoesNotInstallRelayTransportWithoutRelayAddrs() throws Exception {
        Host endpoint = Libp2pTunnelTransportRuntime.createHost(
                EndpointPeerIdentity.loadOrCreate(tempDir.resolve("endpoint-direct.key")).privateKey(),
                new String[]{"/ip4/127.0.0.1/tcp/0"},
                Collections.emptyList());
        try {
            endpoint.start().get(10, TimeUnit.SECONDS);

            assertFalse(endpoint.listenAddresses().stream()
                    .map(Object::toString)
                    .anyMatch(address -> address.contains("/p2p-circuit")),
                    "direct-only endpoint unexpectedly advertised a relay circuit address");
        } finally {
            endpoint.stop().get(10, TimeUnit.SECONDS);
        }
    }

    private void endpointHostCanOpenProtocolStream(String protocolID, java.util.function.Consumer<Host> installer) throws Exception {
        Host endpoint = Libp2pTunnelTransportRuntime.createHost(
                EndpointPeerIdentity.loadOrCreate(tempDir.resolve("endpoint.key")).privateKey(),
                "/ip4/127.0.0.1/tcp/0");
        Host edge = Libp2pTunnelTransportRuntime.createHost(
                EndpointPeerIdentity.loadOrCreate(tempDir.resolve("edge.key")).privateKey(),
                "/ip4/127.0.0.1/tcp/0");
        CountDownLatch accepted = new CountDownLatch(1);

        installer.accept(endpoint);
        edge.addProtocolHandler(new io.libp2p.core.multistream.StrictProtocolBinding<Void>(
                protocolID,
                new io.libp2p.protocol.ProtocolHandler<Void>(Long.MAX_VALUE, Long.MAX_VALUE) {
                    @Override
                    protected CompletableFuture<Void> onStartInitiator(Stream stream) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    protected CompletableFuture<Void> onStartResponder(Stream stream) {
                        accepted.countDown();
                        return CompletableFuture.completedFuture(null);
                    }
                }) {
        });

        try {
            endpoint.start().get(10, TimeUnit.SECONDS);
            edge.start().get(10, TimeUnit.SECONDS);

            String address = edge.listenAddresses().get(0).withP2P(edge.getPeerId()).toString();
            Connection connection = endpoint.getNetwork()
                    .connect(PeerId.fromBase58(edge.getPeerId().toBase58()), io.libp2p.core.multiformats.Multiaddr.fromString(address))
                    .get(10, TimeUnit.SECONDS);
            StreamPromise<Object> promise = endpoint.newStream(
                    Arrays.asList(protocolID),
                    connection);
            Stream stream = promise.getStream().get(10, TimeUnit.SECONDS);
            stream.getProtocol().get(10, TimeUnit.SECONDS);

            assertTrue(accepted.await(5, TimeUnit.SECONDS), "register protocol was not accepted");
        } finally {
            endpoint.stop().get(10, TimeUnit.SECONDS);
            edge.stop().get(10, TimeUnit.SECONDS);
        }
    }
}
