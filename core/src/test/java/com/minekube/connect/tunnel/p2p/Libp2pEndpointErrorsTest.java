package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class Libp2pEndpointErrorsTest {

    @Test
    void treatsClosedChannelDuringReconnectAsTransient() {
        Throwable error = new IllegalStateException(
                "failed to connect libp2p Connect edge peer 12D3Koo",
                new ExecutionException(new RuntimeException(
                        "io.libp2p.core.ConnectionClosedException: Channel closed [id: 0x1]")));

        assertTrue(Libp2pEndpointErrors.isTransientConnectError(error));
        assertTrue(Libp2pEndpointErrors.summary(error).contains("ConnectionClosedException"));
    }

    @Test
    void treatsInvalidRemotePeerAsTransientAnycastMismatch() {
        Throwable error = new IllegalStateException(
                "failed to connect libp2p Connect edge peer 12D3KooExpected",
                new ExecutionException(new RuntimeException(
                        "io.libp2p.security.InvalidRemotePubKey: remote peer ID does not match expected peer")));

        assertTrue(Libp2pEndpointErrors.isTransientConnectError(error));
        assertTrue(Libp2pEndpointErrors.isEdgePeerMismatch(error));
        assertTrue(Libp2pEndpointErrors.summary(error).contains("InvalidRemotePubKey"));
    }

    @Test
    void keepsUnexpectedProtocolErrorsNonTransient() {
        Throwable error = new IllegalStateException("encode libp2p registration frame");

        assertFalse(Libp2pEndpointErrors.isTransientConnectError(error));
        assertFalse(Libp2pEndpointErrors.isEdgePeerMismatch(error));
    }
}
