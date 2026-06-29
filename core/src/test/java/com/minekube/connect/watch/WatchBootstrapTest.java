package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.Headers;
import org.junit.jupiter.api.Test;

class WatchBootstrapTest {
    @Test
    void parsesLibp2pBootstrapHeaders() {
        Headers headers = new Headers.Builder()
                .add(WatchBootstrap.LIBP2P_EDGE_ADDRS_HEADER, " /ip4/127.0.0.1/tcp/4001/p2p/a , /ip4/127.0.0.1/tcp/4001/p2p/b ")
                .add(WatchBootstrap.LIBP2P_RELAY_ADDRS_HEADER, "/ip4/127.0.0.1/tcp/4001/p2p/a")
                .build();

        WatchBootstrap bootstrap = WatchBootstrap.fromHeaders(headers);

        assertTrue(bootstrap.hasLibp2p());
        assertEquals(2, bootstrap.libp2pEdgeAddrs().size());
        assertEquals("/ip4/127.0.0.1/tcp/4001/p2p/a", bootstrap.libp2pEdgeAddrs().get(0));
        assertEquals("/ip4/127.0.0.1/tcp/4001/p2p/a", bootstrap.libp2pRelayAddrs().get(0));
    }

    @Test
    void emptyHeadersDisableLibp2pBootstrap() {
        WatchBootstrap bootstrap = WatchBootstrap.fromHeaders(Headers.of());

        assertFalse(bootstrap.hasLibp2p());
    }
}
