package com.minekube.connect.share.forge.v1_20_1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ForgeLoginNegotiationTest {
    @Test
    fun `approved share login enters Forge negotiation before acceptance`() {
        val listener = FakeLoginListener()
        var accepted = false

        ForgeLoginNegotiation.continueApprovedLogin(
            listener,
            Runnable { accepted = true },
        )

        assertEquals(FakeLoginState.NEGOTIATING, listener.state)
        assertFalse(accepted)
    }

    private class FakeLoginListener {
        var state = FakeLoginState.HELLO
    }

    private enum class FakeLoginState {
        HELLO,
        NEGOTIATING,
        READY_TO_ACCEPT,
    }
}
