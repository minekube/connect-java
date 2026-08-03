package com.minekube.connect.share.fabric

import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.SavedFriend
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FriendActivityMonitorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `refresh keeps only reachable confirmed friend activity`() = runTest {
        val playing = friend("playing", "Robin")
        val unreachable = friend("offline", "Bob")
        val monitor = FriendActivityMonitor.testing(
            friends = { listOf(playing, unreachable) },
            query = { friend ->
                if (friend.peerId == playing.peerId) {
                    FriendActivity(
                        FriendActivityKind.PLAYING_SERVER,
                        "Hypixel",
                    ).right()
                } else {
                    FriendRequestFailure.Unreachable.left()
                }
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        monitor.refresh()

        assertEquals(
            FriendActivity(FriendActivityKind.PLAYING_SERVER, "Hypixel"),
            monitor.state.value.getValue("playing"),
        )
        assertEquals(setOf("playing"), monitor.state.value.keys)
    }

    private fun friend(peerId: String, name: String) = SavedFriend(
        peerId = peerId,
        publicKeyBase64 = "key",
        shareId = java.util.UUID.randomUUID(),
        capability = "friend-capability-123456789",
        connectAddress = null,
        displayName = name,
    )
}
