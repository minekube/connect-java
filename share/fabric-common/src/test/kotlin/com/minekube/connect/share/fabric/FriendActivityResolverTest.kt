package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import kotlin.test.Test
import kotlin.test.assertEquals

class FriendActivityResolverTest {
    @Test
    fun `enabled singleplayer sharing publishes the world as playing`() {
        assertEquals(
            FriendActivity(FriendActivityKind.HOSTING_WORLD, "Survival"),
            FriendActivityResolver.resolve(
                worldAvailable = true,
                worldSharingActive = true,
                worldName = "Survival",
                externalServerName = null,
            ),
        )
    }

    @Test
    fun `singleplayer stays private until sharing is enabled`() {
        assertEquals(
            FriendActivity(FriendActivityKind.ONLINE),
            FriendActivityResolver.resolve(
                worldAvailable = true,
                worldSharingActive = false,
                worldName = "Private World",
                externalServerName = null,
            ),
        )
    }

    @Test
    fun `external multiplayer remains requestable without exposing its address`() {
        assertEquals(
            FriendActivity(FriendActivityKind.PLAYING_SERVER, "Hypixel"),
            FriendActivityResolver.resolve(
                worldAvailable = false,
                worldSharingActive = true,
                worldName = null,
                externalServerName = "Hypixel",
            ),
        )
    }
}
