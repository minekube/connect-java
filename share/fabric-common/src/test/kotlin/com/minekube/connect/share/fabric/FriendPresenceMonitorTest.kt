package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.SavedFriend
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FriendPresenceMonitorTest {
    @Test
    fun `refresh projects online state without exposing saved routes`() = runTest {
        val online = friend(
            peerId = "12D3KooWOnline",
            address = "online.play.minekube.net",
        )
        val offline = friend(
            peerId = "12D3KooWOffline",
            address = "offline.play.minekube.net",
        )
        val monitor = FriendPresenceMonitor.testing(
            friends = { listOf(online, offline) },
            probe = FriendStatusProbe { address ->
                if (address.startsWith("online")) {
                    Either.Right(ServerPresence("Robin's World"))
                } else {
                    Either.Left(StatusProbeError.EndpointOffline)
                }
            },
        )

        monitor.refresh()

        val presence = monitor.state.value
        assertTrue(presence.getValue(online.peerId).online)
        assertEquals(
            "Robin's World",
            presence.getValue(online.peerId).description,
        )
        assertFalse(presence.getValue(offline.peerId).online)
        assertFalse(presence.toString().contains("capability-secret"))
    }

    @Test
    fun `online notification fires once per transition and respects preference`() {
        val tracker = FriendOnlineTracker()
        val online = RemoteFriendPresence(
            peerId = "peer-online",
            displayName = "Robin",
            online = true,
            description = "Robin's World",
            notifyWhenOnline = true,
        )
        val muted = online.copy(
            peerId = "peer-muted",
            displayName = "Muted",
            notifyWhenOnline = false,
        )

        assertEquals(
            listOf(online),
            tracker.update(mapOf(online.peerId to online, muted.peerId to muted)),
        )
        assertTrue(
            tracker.update(mapOf(online.peerId to online)).isEmpty(),
        )
        tracker.update(
            mapOf(online.peerId to online.copy(online = false)),
        )

        assertEquals(
            listOf(online),
            tracker.update(mapOf(online.peerId to online)),
        )
    }

    private fun friend(
        peerId: String,
        address: String,
    ) = SavedFriend(
        peerId = peerId,
        publicKeyBase64 = "cHVibGljLWtleQ==",
        shareId = UUID.randomUUID(),
        capability = "capability-secret",
        connectAddress = address,
        displayName = peerId.takeLast(6),
        permissions = FriendPermissions(),
    )
}
