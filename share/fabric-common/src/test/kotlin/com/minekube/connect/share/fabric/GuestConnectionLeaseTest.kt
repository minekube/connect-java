package com.minekube.connect.share.fabric

import kotlin.test.Test
import kotlin.test.assertEquals

class GuestConnectionLeaseTest {
    @Test
    fun `lease survives connect screen and closes after disconnect`() {
        val closed = mutableListOf<String>()
        var now = 0L
        val lease = GuestConnectionLease(nowNanos = { now })

        lease.hold(closeable("proxy", closed), closeable("browser", closed))
        lease.connectionChanged(false)
        lease.connectionChanged(true)
        assertEquals(emptyList(), closed)

        lease.connectionChanged(false)

        assertEquals(listOf("proxy", "browser"), closed)
    }

    @Test
    fun `lease closes when Minecraft never establishes a connection`() {
        val closed = mutableListOf<String>()
        var now = 0L
        val lease = GuestConnectionLease(
            nowNanos = { now },
            connectTimeoutNanos = 30,
        )

        lease.hold(closeable("proxy", closed), closeable("browser", closed))
        now = 31
        lease.connectionChanged(false)

        assertEquals(listOf("proxy", "browser"), closed)
    }

    @Test
    fun `replacement closes the previous lease in ownership order`() {
        val closed = mutableListOf<String>()
        val lease = GuestConnectionLease(nowNanos = { 0 })

        lease.hold(closeable("first-proxy", closed), closeable("first-browser", closed))
        lease.hold(closeable("second-proxy", closed), closeable("second-browser", closed))
        lease.close()

        assertEquals(
            listOf(
                "first-proxy",
                "first-browser",
                "second-proxy",
                "second-browser",
            ),
            closed,
        )
    }

    private fun closeable(
        name: String,
        closed: MutableList<String>,
    ) = AutoCloseable { closed += name }
}
