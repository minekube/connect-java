package com.minekube.connect.share.direct

import com.minekube.connect.tunnel.p2p.DirectP2pSession
import io.netty.util.AttributeKey
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap

object DirectSessionAttributes {
    @JvmField
    val SESSION: AttributeKey<DirectP2pSession> =
        AttributeKey.valueOf("connect-share:direct-session")
}

object DirectSessionRegistry {
    private val pending = ConcurrentHashMap<Int, PendingSession>()

    fun register(
        sourcePort: Int,
        session: DirectP2pSession,
        nowNanos: Long = System.nanoTime(),
    ): AutoCloseable {
        require(sourcePort in 1..65_535) { "Direct source port is invalid" }
        purgeExpired(nowNanos)
        val registered = PendingSession(
            session = session,
            expiresAtNanos = nowNanos + REGISTRATION_TTL_NANOS,
        )
        check(pending.putIfAbsent(sourcePort, registered) == null) {
            "A direct session is already registered for this source port"
        }
        return AutoCloseable {
            pending.remove(sourcePort, registered)
        }
    }

    fun claim(
        remoteAddress: SocketAddress?,
        nowNanos: Long = System.nanoTime(),
    ): DirectP2pSession? {
        val address = remoteAddress as? InetSocketAddress ?: return null
        if (!address.address.isLoopbackAddress) {
            return null
        }
        purgeExpired(nowNanos)
        return pending.remove(address.port)
            ?.takeIf { it.expiresAtNanos >= nowNanos }
            ?.session
    }

    internal fun clear() {
        pending.clear()
    }

    private fun purgeExpired(nowNanos: Long) {
        pending.entries.removeIf { it.value.expiresAtNanos < nowNanos }
    }

    private data class PendingSession(
        val session: DirectP2pSession,
        val expiresAtNanos: Long,
    )

    private const val REGISTRATION_TTL_NANOS = 10_000_000_000L
}
