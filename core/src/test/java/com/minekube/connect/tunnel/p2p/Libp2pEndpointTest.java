package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minekube.connect.api.logger.ConnectLogger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Libp2pEndpointTest {

    @Test
    void reservesNextRelayWhenFirstRelayPeerMismatches() {
        ConnectLogger logger = mock(ConnectLogger.class);
        List<String> reserved = new ArrayList<>();

        List<String> addrs = Libp2pEndpointRuntime.reserveRelayAddrs(
                Arrays.asList(
                        "/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-a",
                        "/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-b"),
                "endpoint-peer",
                relayAddr -> {
                    if (relayAddr.endsWith("/p2p/edge-a")) {
                        throw new IllegalStateException("io.libp2p.security.InvalidRemotePubKey");
                    }
                    reserved.add(relayAddr);
                },
                logger);

        assertEquals(List.of("/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-b/p2p-circuit/p2p/endpoint-peer"), addrs);
        assertEquals(List.of("/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-b"), reserved);
        verify(logger, atLeastOnce()).warn(org.mockito.ArgumentMatchers.contains("fly-anycast-reached-different-edge-peer"));
    }

    @Test
    void failsWhenAllRelayReservationsFail() {
        ConnectLogger logger = mock(ConnectLogger.class);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> Libp2pEndpointRuntime.reserveRelayAddrs(
                List.of("/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-a"),
                "endpoint-peer",
                relayAddr -> {
                    throw new IllegalStateException("io.libp2p.security.InvalidRemotePubKey");
                },
                logger));

        assertEquals("failed to reserve any configured libp2p relay", error.getMessage());
    }

    @Test
    void retriesSingleAnycastRelayReservation() {
        ConnectLogger logger = mock(ConnectLogger.class);
        AtomicInteger attempts = new AtomicInteger();

        List<String> addrs = Libp2pEndpointRuntime.reserveRelayAddrs(
                List.of("/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-a"),
                "endpoint-peer",
                relayAddr -> {
                    if (attempts.incrementAndGet() < 4) {
                        throw new IllegalStateException("io.libp2p.security.InvalidRemotePubKey");
                    }
                },
                logger);

        assertEquals(4, attempts.get());
        assertEquals(List.of(
                "/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-a/p2p-circuit/p2p/endpoint-peer"), addrs);
    }

    @Test
    void repeatsRegisterAddressesForAnycastBootstrapRetries() {
        assertEquals(
                List.of("edge-a", "edge-a", "edge-a", "edge-a"),
                Libp2pEndpointRuntime.registerAttemptAddresses(List.of("edge-a"), 4));
        assertEquals(
                List.of("edge-a", "edge-b", "edge-a", "edge-b", "edge-a", "edge-b"),
                Libp2pEndpointRuntime.registerAttemptAddresses(List.of("edge-a", "edge-b"), 3));
    }
}
