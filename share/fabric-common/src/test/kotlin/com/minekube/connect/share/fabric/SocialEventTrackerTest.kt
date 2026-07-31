package com.minekube.connect.share.fabric

import com.minekube.connect.share.fabric.ui.FriendSummary
import com.minekube.connect.share.fabric.ui.FriendsUiState
import com.minekube.connect.share.fabric.ui.OutgoingFriendRequestSummary
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocialEventTrackerTest {
    @Test
    fun `accepted removed and server activity transitions each emit once`() {
        val tracker = SocialEventTracker()
        val outgoing = FriendsUiState(
            outgoingRequests = listOf(
                OutgoingFriendRequestSummary("peer", "Robin"),
            ),
        )
        assertTrue(tracker.update(outgoing).isEmpty())

        val confirmed = FriendsUiState(friends = listOf(friend()))
        assertEquals(
            listOf(SocialEvent.FriendAccepted("Robin")),
            tracker.update(confirmed),
        )
        assertTrue(tracker.update(confirmed).isEmpty())

        val playing = FriendsUiState(
            friends = listOf(
                friend().copy(
                    activityKind = FriendActivityKind.PLAYING_SERVER,
                    activityDescription = "Hypixel",
                    canRequestJoin = true,
                ),
            ),
        )
        assertEquals(
            listOf(SocialEvent.PlayingServer("Robin", "Hypixel")),
            tracker.update(playing),
        )
        assertEquals(
            listOf(SocialEvent.FriendRemoved("Robin")),
            tracker.update(FriendsUiState()),
        )
    }

    private fun friend() = FriendSummary(
        peerId = "peer",
        displayName = "Robin",
        connectAvailable = true,
        permissions = FriendPermissions(),
    )
}
