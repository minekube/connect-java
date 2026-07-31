package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.friend.FriendControlContext
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendControlResponse
import com.minekube.connect.share.friend.FriendStore
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.io.TempDir

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FriendRequestServerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `remote acceptance stores sender and returns signed host card`() = runTest {
        val senderIssuer = issuer("sender")
        val hostIssuer = issuer("host")
        val senderCard = senderIssuer.issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!
            .payload.peerId
        val admission = admission()
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission,
            issuer = hostIssuer,
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            now = { NOW },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val response = server.handle(
            FriendControlContext(
                ingress = Ingress.DIRECT_LAN,
                directPeerId = senderPeerId,
            ),
            request(senderCard),
        ).toCompletableFuture()
        runCurrent()

        val pending = admission.pending.value.single()
        assertEquals(AdmissionPurpose.FRIEND, pending.purpose)
        assertEquals("bob", pending.identity.name)
        admission.answer(pending.requestId, allow = true)
        runCurrent()

        val accepted = assertIs<FriendControlResponse.Accepted>(
            response.getNow(null),
        )
        assertTrue(
            ShareInviteCodec.decode(accepted.invitation, NOW).isRight(),
        )
        assertEquals(senderPeerId, hostStore.all().single().peerId)
        assertTrue(hostStore.all().single().permissions.canJoinAutomatically)
    }

    @Test
    fun `non libp2p ingress and direct identity mismatch never create trust`() =
        runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val admission = admission()
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission,
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            now = { NOW },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val mismatch = server.handle(
            FriendControlContext(
                ingress = Ingress.DIRECT_LAN,
                directPeerId = "12D3KooWWrong",
            ),
            request(senderCard),
        ).toCompletableFuture()
        runCurrent()
        assertEquals(FriendControlResponse.Invalid, mismatch.getNow(null))
        assertTrue(admission.pending.value.isEmpty())

        val connect = server.handle(
            FriendControlContext(Ingress.CONNECT, directPeerId = null),
            request(senderCard),
        ).toCompletableFuture()
        runCurrent()

        assertEquals(FriendControlResponse.Invalid, connect.getNow(null))
        assertTrue(admission.pending.value.isEmpty())
        assertTrue(hostStore.all().isEmpty())
    }

    @Test
    fun `crossed outgoing request confirms friendship without another prompt`() =
        runTest {
            val senderCard = issuer("sender").issue(NOW).getOrNull()!!
            val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
                .getOrNull()!!
                .payload.peerId
            val admission = admission()
            val hostStore = FriendStore(tempDir.resolve("host-store"))
            hostStore.sendRequest(senderCard, "bob", NOW)
            var relationshipsChanged = 0
            val server = FriendRequestServer(
                scope = backgroundScope,
                admission = admission,
                issuer = issuer("host"),
                receiver = FriendCardReceiver(hostStore),
                friendStore = hostStore,
                now = { NOW },
                ioDispatcher = StandardTestDispatcher(testScheduler),
                onRelationshipChanged = { relationshipsChanged++ },
            )

            val response = server.handle(
                FriendControlContext(
                    ingress = Ingress.DIRECT_LAN,
                    directPeerId = senderPeerId,
                ),
                request(senderCard),
            ).toCompletableFuture()

            assertIs<FriendControlResponse.Accepted>(response.await())
            assertTrue(admission.pending.value.isEmpty())
            val confirmed = hostStore.all().single()
            assertEquals(senderPeerId, confirmed.peerId)
            assertTrue(confirmed.permissions.canJoinAutomatically)
            assertTrue(hostStore.outgoingRequests().isEmpty())
            assertEquals(1, relationshipsChanged)
        }

    private fun kotlinx.coroutines.test.TestScope.admission() =
        AdmissionController(
            scope = backgroundScope,
            timeout = 30.seconds,
            maxPending = 16,
            connectedCount = { 0 },
            maxGuests = { 8 },
        )

    private fun issuer(name: String) = FriendCardIssuer(
        dataDirectory = tempDir.resolve(name),
        connectAddress = { "$name.play.minekube.net" },
    )

    private fun request(card: String) = FriendControlRequest(
        requestId = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        ),
        displayName = "bob",
        invitation = card,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
    }
}
