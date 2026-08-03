package com.minekube.connect.share.fabric.v1_20_1

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Minecraft1201LoginContinuationTest {
    @AfterTest
    fun resetContinuation() {
        Minecraft1201LoginBridge.resetLoginContinuationForTests()
    }

    @Test
    fun `fabric accepts an approved login immediately`() {
        var accepted = false

        Minecraft1201LoginBridge.continueApprovedLogin(
            listener = Any(),
            accept = Runnable { accepted = true },
        )

        assertTrue(accepted)
    }

    @Test
    fun `loader continuation can finish native negotiation first`() {
        val listener = Any()
        var accepted = false
        var observedListener: Any? = null
        var deferredAccept: Runnable? = null
        Minecraft1201LoginBridge.installLoginContinuation { actual, accept ->
            observedListener = actual
            deferredAccept = accept
        }

        Minecraft1201LoginBridge.continueApprovedLogin(
            listener = listener,
            accept = Runnable { accepted = true },
        )

        assertSame(listener, observedListener)
        assertFalse(accepted)
        deferredAccept!!.run()
        assertTrue(accepted)
    }
}
