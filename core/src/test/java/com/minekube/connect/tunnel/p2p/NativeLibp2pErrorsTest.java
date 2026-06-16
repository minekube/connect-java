package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class NativeLibp2pErrorsTest {

    @Test
    void treatsClosedChannelDuringReconnectAsTransient() {
        Throwable error = new IllegalStateException(
                "failed to connect native libp2p moxy peer 12D3Koo",
                new ExecutionException(new RuntimeException(
                        "io.libp2p.core.ConnectionClosedException: Channel closed [id: 0x1]")));

        assertTrue(NativeLibp2pErrors.isTransientConnectError(error));
        assertTrue(NativeLibp2pErrors.summary(error).contains("ConnectionClosedException"));
    }

    @Test
    void keepsUnexpectedProtocolErrorsNonTransient() {
        Throwable error = new IllegalStateException("encode native registration frame");

        assertFalse(NativeLibp2pErrors.isTransientConnectError(error));
    }
}
