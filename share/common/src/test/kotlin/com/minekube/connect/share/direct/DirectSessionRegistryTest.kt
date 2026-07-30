package com.minekube.connect.share.direct

import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pRoute
import com.minekube.connect.tunnel.p2p.DirectP2pSession
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectSessionRegistryTest {
    @AfterTest
    fun clear() {
        DirectSessionRegistry.clear()
    }

    @Test
    fun `loopback source port claims a direct session exactly once`() {
        DirectSessionRegistry.register(
            sourcePort = 41_234,
            session = SESSION,
            nowNanos = 100,
        )
        val remote = InetSocketAddress(
            InetAddress.getLoopbackAddress(),
            41_234,
        )

        assertEquals(SESSION, DirectSessionRegistry.claim(remote, nowNanos = 101))
        assertNull(DirectSessionRegistry.claim(remote, nowNanos = 102))
    }

    @Test
    fun `non-loopback and expired registrations are never claimed`() {
        DirectSessionRegistry.register(
            sourcePort = 41_234,
            session = SESSION,
            nowNanos = 100,
        )

        assertNull(
            DirectSessionRegistry.claim(
                InetSocketAddress("192.168.1.20", 41_234),
                nowNanos = 101,
            ),
        )
        assertNull(
            DirectSessionRegistry.claim(
                InetSocketAddress(
                    InetAddress.getLoopbackAddress(),
                    41_234,
                ),
                nowNanos = 10_000_000_101L,
            ),
        )
    }

    private companion object {
        val SESSION = DirectP2pSession(
            "12D3KooWGuest",
            DirectP2pAuthMode.OFFLINE,
            DirectP2pRoute.LAN,
            "connection-1",
        )
    }
}
