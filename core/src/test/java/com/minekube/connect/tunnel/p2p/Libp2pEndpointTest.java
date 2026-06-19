package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minekube.connect.api.logger.ConnectLogger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class Libp2pEndpointTest {

    @Test
    void reservesNextRelayWhenFirstRelayPeerMismatches() {
        ConnectLogger logger = mock(ConnectLogger.class);
        List<String> reserved = new ArrayList<>();

        List<String> addrs = Libp2pEndpoint.reserveRelayAddrs(
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
        verify(logger).warn(org.mockito.ArgumentMatchers.contains("fly-anycast-reached-different-edge-peer"));
    }

    @Test
    void failsWhenAllRelayReservationsFail() {
        ConnectLogger logger = mock(ConnectLogger.class);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> Libp2pEndpoint.reserveRelayAddrs(
                List.of("/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/edge-a"),
                "endpoint-peer",
                relayAddr -> {
                    throw new IllegalStateException("io.libp2p.security.InvalidRemotePubKey");
                },
                logger));

        assertEquals("failed to reserve any configured libp2p relay", error.getMessage());
    }
}
