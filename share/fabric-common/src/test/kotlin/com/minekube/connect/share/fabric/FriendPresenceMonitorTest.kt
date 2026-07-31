package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.SavedFriend
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FriendPresenceMonitorTest {
    @Test
    fun `refresh loads persisted friends only on its IO dispatcher`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var loads = 0
        val monitor = FriendPresenceMonitor.testing(
            friends = {
                loads++
                emptyList()
            },
            probe = FriendStatusProbe {
                error("no friends should be probed")
            },
            ioDispatcher = io,
        )

        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            monitor.refresh()
        }

        assertEquals(0, loads)
        runCurrent()
        assertEquals(1, loads)
        refresh.await()
    }

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
    fun `direct LAN status is preferred before Connect presence`() = runTest {
        val nearby = friend(
            peerId = "12D3KooWNearby",
            address = "nearby.play.minekube.net",
        )
        val connectProbes = mutableListOf<String>()
        val monitor = FriendPresenceMonitor.testing(
            friends = { listOf(nearby) },
            directProbe = {
                ServerPresence("Robin's LAN World")
            },
            probe = FriendStatusProbe { address ->
                connectProbes += address
                Either.Right(ServerPresence("Wrong Connect World"))
            },
        )

        monitor.refresh()

        val presence = monitor.state.value.getValue(nearby.peerId)
        assertTrue(presence.online)
        assertEquals(ShareRoute.DIRECT_LAN, presence.route)
        assertEquals("Robin's LAN World", presence.description)
        assertTrue(connectProbes.isEmpty())
    }

    @Test
    fun `direct presence probing preserves coroutine cancellation`() = runTest {
        val monitor = FriendPresenceMonitor.testing(
            friends = {
                listOf(
                    friend(
                        peerId = "12D3KooWCancelled",
                        address = "cancelled.play.minekube.net",
                    ),
                )
            },
            directProbe = {
                throw CancellationException("cancelled")
            },
            probe = FriendStatusProbe {
                Either.Right(ServerPresence("must not run"))
            },
        )

        assertFailsWith<CancellationException> {
            monitor.refresh()
        }
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

    @Test
    fun `refresh never probes this profiles own Connect endpoint as a friend`() =
        runTest {
            val copied = friend(
                peerId = "12D3KooWCopiedEndpoint",
                address = "mine.play.minekube.net",
            )
            val probed = mutableListOf<String>()
            val monitor = FriendPresenceMonitor.testing(
                friends = { listOf(copied) },
                ownConnectAddress = { "mine.play.minekube.net" },
                probe = FriendStatusProbe { address ->
                    probed += address
                    Either.Right(ServerPresence("Wrong self presence"))
                },
            )

            monitor.refresh()

            assertTrue(probed.isEmpty())
            assertFalse(monitor.state.value.getValue(copied.peerId).online)
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
