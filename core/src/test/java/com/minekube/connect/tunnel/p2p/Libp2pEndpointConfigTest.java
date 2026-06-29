package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Libp2pEndpointConfigTest {
    private static final String MOXY_PEER = "12D3KooWNXa3WQenRYKVJCHxcadnp3JPcxRALCqk97qpMiqAG1tt";
    private static final String SECOND_MOXY_PEER = "12D3KooWEzZpASrUwA3s8CM3UCDCCYjQzfh91ZyJnxRqaZ9xTi31";

    @Test
    void disabledWhenRegisterAddressIsMissing() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromEnvironment(Map.of());

        assertFalse(config.enabled());
        assertEquals(List.of("session", "status"), config.capabilities());
    }

    @Test
    void bootstrapConfigEnablesWatchlessWhenSupportedByEdge() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromBootstrap(
                List.of("/dns4/connect.example/tcp/4001/p2p/" + MOXY_PEER),
                List.of("/dns4/connect.example/tcp/4001/p2p/" + MOXY_PEER),
                true);

        assertTrue(config.enabled());
        assertEquals(List.of("/ip4/127.0.0.1/tcp/0"), config.listenAddrs());
        assertEquals(List.of("session", "status", "watchless"), config.capabilities());
    }

    @Test
    void bootstrapConfigKeepsLegacyWatchCapabilityWhenWatchlessIsUnsupported() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromBootstrap(
                List.of("/dns4/connect.example/tcp/4001/p2p/" + MOXY_PEER),
                List.of("/dns4/connect.example/tcp/4001/p2p/" + MOXY_PEER),
                false);

        assertTrue(config.enabled());
        assertEquals(List.of("session", "status"), config.capabilities());
    }

    @Test
    void bootstrapConfigEnablesManagedLibp2p() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromBootstrap(
                java.util.List.of("/dns4/connect.example/tcp/4001/p2p/" + MOXY_PEER),
                java.util.List.of("/dns4/connect.example/tcp/4001/p2p/" + SECOND_MOXY_PEER));

        assertTrue(config.enabled());
        assertEquals("/ip4/127.0.0.1/tcp/0", config.listenAddrs().get(0));
        assertEquals(1, config.registerAddrs().size());
        assertEquals(1, config.relayAddrs().size());
    }

    @Test
    void bootstrapConfigEnablesManagedLibp2p() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromBootstrap(
                java.util.List.of("/dns4/connect.example/tcp/4001/p2p/" + MOXY_PEER),
                java.util.List.of("/dns4/connect.example/tcp/4001/p2p/" + SECOND_MOXY_PEER));

        assertTrue(config.enabled());
        assertEquals("/ip4/127.0.0.1/tcp/0", config.listenAddrs().get(0));
        assertEquals(1, config.registerAddrs().size());
        assertEquals(1, config.relayAddrs().size());
    }

    @Test
    void parsesRegisterListenAndAdvertiseAddresses() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromEnvironment(Map.of(
                "CONNECT_LIBP2P_EDGE_ADDR", " /ip4/127.0.0.1/tcp/1/p2p/a , /ip4/127.0.0.1/tcp/2/p2p/b ",
                "CONNECT_LIBP2P_LISTEN_ADDR", "/ip4/127.0.0.1/tcp/0",
                "CONNECT_LIBP2P_ADVERTISE_ADDRS", "/ip4/127.0.0.1/tcp/1234/p2p/c",
                "CONNECT_LIBP2P_RELAY_ADDRS", " /ip4/127.0.0.1/tcp/4001/p2p/relay1 , /ip4/127.0.0.1/tcp/4002/p2p/relay2 "));

        assertTrue(config.enabled());
        assertEquals(2, config.registerAddrs().size());
        assertEquals("/ip4/127.0.0.1/tcp/0", config.listenAddrs().get(0));
        assertEquals("/ip4/127.0.0.1/tcp/1234/p2p/c", config.advertiseAddrs().get(0));
        assertEquals(2, config.relayAddrs().size());
        assertEquals("/ip4/127.0.0.1/tcp/4001/p2p/relay1", config.relayAddrs().get(0));
    }

    @Test
    void derivesEndpointRelayCircuitAddresses() {
        assertEquals(
                "/ip4/127.0.0.1/tcp/4001/p2p/relay1/p2p-circuit/p2p/endpoint1",
                Libp2pEndpointRuntime.relayCircuitAddr(
                        "/ip4/127.0.0.1/tcp/4001/p2p/relay1",
                        "endpoint1"));
    }

    @Test
    void extractsConfiguredConnectEdgePeerIds() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromEnvironment(Map.of(
                "CONNECT_LIBP2P_EDGE_ADDR", "/dns4/connect-proxy-staging.fly.dev/tcp/4001/p2p/" + MOXY_PEER));

        assertEquals(1, config.edgePeerIds().size());
        assertEquals(MOXY_PEER, config.edgePeerIds().get(0));
    }

    @Test
    void authorizesRelayPeerIdsAlongsideRegisterPeerIds() {
        Libp2pEndpointConfig config = Libp2pEndpointConfig.fromEnvironment(Map.of(
                "CONNECT_LIBP2P_EDGE_ADDR", "/dns4/primary-edge.example/tcp/4001/p2p/" + MOXY_PEER,
                "CONNECT_LIBP2P_RELAY_ADDRS",
                        "/dns4/primary-edge.example/tcp/4001/p2p/" + MOXY_PEER
                                + ",/dns4/second-edge.example/tcp/4001/p2p/" + SECOND_MOXY_PEER));

        assertEquals(2, config.edgePeerIds().size());
        assertEquals(MOXY_PEER, config.edgePeerIds().get(0));
        assertEquals(SECOND_MOXY_PEER, config.edgePeerIds().get(1));
    }

    @Test
    void generatesDistinctEndpointInstanceIds() {
        assertNotEquals(
                Libp2pEndpointRuntime.newEndpointInstanceId(),
                Libp2pEndpointRuntime.newEndpointInstanceId());
    }
}
