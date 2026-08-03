package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FabricDirectAuthenticationPolicyTest {
    @Test
    fun `failed online authentication never downgrades to offline`() {
        assertIs<Either.Left<DirectOnlineAuthenticationRequired>>(
            FabricDirectAuthenticationPolicy.validate(
                DirectP2pAuthMode.ONLINE,
                minecraftAuthenticated = false,
            ),
        )
    }

    @Test
    fun `explicit offline mode may proceed as unverified`() {
        assertIs<Either.Right<Unit>>(
            FabricDirectAuthenticationPolicy.validate(
                DirectP2pAuthMode.OFFLINE,
                minecraftAuthenticated = false,
            ),
        )
    }

    @Test
    fun `explicit offline tunnel bypasses Mojang login with an offline profile`() {
        assertEquals(
            DirectMinecraftAuthentication.OFFLINE_PROFILE,
            FabricDirectAuthenticationPolicy.minecraftAuthentication(
                DirectP2pAuthMode.OFFLINE,
            ),
        )
    }

    @Test
    fun `online tunnel retains Mojang login`() {
        assertEquals(
            DirectMinecraftAuthentication.MOJANG,
            FabricDirectAuthenticationPolicy.minecraftAuthentication(
                DirectP2pAuthMode.ONLINE,
            ),
        )
    }
}
