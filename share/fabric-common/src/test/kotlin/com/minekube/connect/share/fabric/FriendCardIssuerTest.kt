package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.friend.FriendStore
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir

class FriendCardIssuerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `friend card uses stable signed identity without an open world`() =
        runBlocking {
            val issuer = FriendCardIssuer(
                dataDirectory = tempDir,
                connectAddress = { "purple-del.play.minekube.net" },
            )

            val first = assertIs<Either.Right<String>>(
                issuer.issue(NOW),
            ).value
            val second = assertIs<Either.Right<String>>(
                issuer.issue(NOW.plusSeconds(60)),
            ).value
            val firstInvite =
                ShareInviteCodec.decode(first, NOW).getOrNull()!!
            val secondInvite = ShareInviteCodec.decode(
                second,
                NOW.plusSeconds(60),
            ).getOrNull()!!

            assertEquals(
                firstInvite.payload.peerId,
                secondInvite.payload.peerId,
            )
            assertEquals(
                firstInvite.payload.shareId,
                secondInvite.payload.shareId,
            )
            assertEquals(
                firstInvite.payload.capability,
                secondInvite.payload.capability,
            )
            assertEquals(
                "purple-del.play.minekube.net",
                firstInvite.payload.connectAddress,
            )
            assertTrue(firstInvite.payload.directCandidates.isEmpty())
        }

    @Test
    fun `receiving a card completes reciprocal pairing after approval`() =
        runBlocking {
            val issuer = FriendCardIssuer(
                dataDirectory = tempDir.resolve("sender"),
                connectAddress = { "sender.play.minekube.net" },
            )
            val card = issuer.issue(NOW).getOrNull()!!
            val store = FriendStore(tempDir.resolve("receiver"))
            val receiver = FriendCardReceiver(store)
            val minecraftUuid = java.util.UUID.fromString(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            )

            val result = receiver.receive(
                invitation = card,
                displayName = "Robin",
                authenticatedMinecraftUuid = minecraftUuid,
                now = NOW,
            )

            assertIs<
                Either.Right<
                    com.minekube.connect.share.friend.SavedFriend,
                    >
                >(result)
            val saved = store.all().single()
            assertEquals("Robin", saved.displayName)
            assertEquals(minecraftUuid, saved.minecraftUuid)
            assertTrue(saved.permissions.canJoinAutomatically)
        }

    @Test
    fun `approved exchange promotes the accepter pending request`() =
        runBlocking {
            val issuer = FriendCardIssuer(
                dataDirectory = tempDir.resolve("sender"),
                connectAddress = { "sender.play.minekube.net" },
            )
            val card = issuer.issue(NOW).getOrNull()!!
            val peerId = ShareInviteCodec.decode(card, NOW)
                .getOrNull()!!
                .payload
                .peerId
            val store = FriendStore(tempDir.resolve("accepter"))
            store.receiveRequest(card, "Robin", NOW)
            val receiver = FriendCardReceiver(store)

            val result = receiver.confirmPending(peerId)

            assertIs<
                Either.Right<
                    com.minekube.connect.share.friend.SavedFriend,
                    >
                >(result)
            assertEquals(peerId, store.all().single().peerId)
            assertTrue(store.pendingRequests().isEmpty())
        }

    @Test
    fun `card issuer resolves the persisted endpoint asynchronously`() =
        runBlocking {
            val issuer = FriendCardIssuer(
                dataDirectory = tempDir,
                connectAddress = {
                    yield()
                    "saved-endpoint.play.minekube.net"
                },
            )

            val card = issuer.issue(NOW).getOrNull()!!
            val invite = ShareInviteCodec.decode(card, NOW).getOrNull()!!

            assertEquals(
                "saved-endpoint.play.minekube.net",
                invite.payload.connectAddress,
            )
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
    }
}
