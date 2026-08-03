package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.right
import com.minekube.connect.share.friend.FriendRelationshipStatus
import com.minekube.connect.share.friend.FriendStore
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class FriendPairingE2ETest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Connect Minecraft route is refused before friend delivery`() =
        runBlocking {
            val now = Instant.parse("2026-07-31T10:30:00Z")
            val hostIssuer = FriendCardIssuer(
                dataDirectory = tempDir.resolve("host"),
                connectAddress = { "host.play.minekube.net" },
            )
            val invitation = hostIssuer.issue(now).getOrNull()!!
            val senderStore = FriendStore(tempDir.resolve("sender"))
            val pairing = FriendPairingClient(
                store = senderStore,
                issuer = FriendCardIssuer(tempDir.resolve("sender")) {
                    "sender.play.minekube.net"
                },
                receiver = FriendCardReceiver(senderStore),
                requestClient = FriendRequestClient(
                    ioDispatcher = Dispatchers.IO,
                ),
                now = { now },
                ioDispatcher = Dispatchers.IO,
            )
            var received = false

            val result = pairing.send(
                invitation = invitation,
                friendDisplayName = "RoboFlax2",
                senderDisplayName = "bob",
                route = {
                    GuestJoinTarget.Connect(
                        "host.play.minekube.net",
                    ).right()
                },
                onReceived = { received = true },
            )

            val failure = assertIs<
                Either.Left<FriendPairingFailure.Route>
                >(result).value
            assertEquals(GuestJoinFailure.NoRoute, failure.error)
            assertFalse(received)
            assertTrue(senderStore.all().isEmpty())
            assertEquals(
                FriendRelationshipStatus.PENDING_OUTGOING,
                senderStore.outgoingRequests()
                    .single()
                    .relationshipStatus,
            )
        }
}
