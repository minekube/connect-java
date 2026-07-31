package com.minekube.connect.share.fabric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendCardExchangeConsentTest {
    private var nowMillis = 1_000L
    private val consent = FriendCardExchangeConsent { nowMillis }

    @Test
    fun `armed Share join returns its peer exactly once`() {
        consent.arm(PEER_ID)

        assertEquals(PEER_ID, consent.consume()?.peerId)
        assertNull(consent.consume())
    }

    @Test
    fun `stale Share join cannot leak a card to a later server`() {
        consent.arm(PEER_ID)
        nowMillis += 121_000

        assertNull(consent.consume())
    }

    @Test
    fun `cancel removes pending consent`() {
        consent.arm(PEER_ID)
        consent.cancel()

        assertNull(consent.consume())
    }

    @Test
    fun `reciprocal pairing requires explicit saved friend permission`() {
        assertFalse(
            FriendCardExchangeConsent.shouldArm(
                savedFriendJoin = true,
                canSeeMyWorlds = null,
            ),
        )
        assertFalse(
            FriendCardExchangeConsent.shouldArm(
                savedFriendJoin = true,
                canSeeMyWorlds = false,
            ),
        )
        assertFalse(
            FriendCardExchangeConsent.shouldArm(
                savedFriendJoin = false,
                canSeeMyWorlds = true,
            ),
        )
        assertTrue(
            FriendCardExchangeConsent.shouldArm(
                savedFriendJoin = true,
                canSeeMyWorlds = true,
            ),
        )
    }

    private companion object {
        const val PEER_ID = "12D3KooWPendingFriend"
    }
}
