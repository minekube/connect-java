package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeLibp2pEndpointConfigTest {

    @Test
    void disabledWhenRegisterAddressIsMissing() {
        NativeLibp2pEndpointConfig config = NativeLibp2pEndpointConfig.fromEnvironment(Map.of());

        assertFalse(config.enabled());
    }

    @Test
    void parsesRegisterListenAndAdvertiseAddresses() {
        NativeLibp2pEndpointConfig config = NativeLibp2pEndpointConfig.fromEnvironment(Map.of(
                "CONNECT_LIBP2P_NATIVE_MOXY_ADDR", " /ip4/127.0.0.1/tcp/1/p2p/a , /ip4/127.0.0.1/tcp/2/p2p/b ",
                "CONNECT_LIBP2P_NATIVE_LISTEN_ADDR", "/ip4/127.0.0.1/tcp/0",
                "CONNECT_LIBP2P_NATIVE_ADVERTISE_ADDRS", "/ip4/127.0.0.1/tcp/1234/p2p/c",
                "CONNECT_LIBP2P_NATIVE_RELAY_ADDRS", " /ip4/127.0.0.1/tcp/4001/p2p/relay1 , /ip4/127.0.0.1/tcp/4002/p2p/relay2 "));

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
                NativeLibp2pEndpoint.relayCircuitAddr(
                        "/ip4/127.0.0.1/tcp/4001/p2p/relay1",
                        "endpoint1"));
    }

    @Test
    void generatesDistinctEndpointInstanceIds() {
        assertNotEquals(
                NativeLibp2pEndpoint.newEndpointInstanceId(),
                NativeLibp2pEndpoint.newEndpointInstanceId());
    }
}
