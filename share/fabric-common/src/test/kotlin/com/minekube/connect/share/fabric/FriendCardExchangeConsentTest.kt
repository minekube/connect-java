package com.minekube.connect.share.fabric

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendCardExchangeConsentTest {
    private var nowMillis = 1_000L
    private val consent = FriendCardExchangeConsent { nowMillis }

    @Test
    fun `armed Share join allows exactly one reciprocal card request`() {
        consent.arm()

        assertTrue(consent.consume())
        assertFalse(consent.consume())
    }

    @Test
    fun `stale Share join cannot leak a card to a later server`() {
        consent.arm()
        nowMillis += 121_000

        assertFalse(consent.consume())
    }

    @Test
    fun `cancel removes pending consent`() {
        consent.arm()
        consent.cancel()

        assertFalse(consent.consume())
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
}
