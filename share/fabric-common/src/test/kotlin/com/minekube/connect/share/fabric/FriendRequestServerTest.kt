package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AuthSource
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.friend.FriendControlContext
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendControlResponse
import com.minekube.connect.share.friend.FriendRemovalRequest
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendActivityRequest
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendAccessPolicy
import com.minekube.connect.share.friend.PresencePrivacy
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
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
        assertFalse(hostStore.all().single().permissions.canJoinAutomatically)
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
    fun `blocked libp2p identity cannot create another friend prompt`() = runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!.payload.peerId
        val admission = admission()
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        hostStore.accept(senderCard, "bob", NOW)
        hostStore.block(senderPeerId, NOW)
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission,
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            now = { NOW.plusSeconds(1) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val response = server.handle(
            FriendControlContext(Ingress.DIRECT_LAN, senderPeerId),
            request(senderCard),
        ).await()

        assertEquals(FriendControlResponse.Declined, response)
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
            assertFalse(confirmed.permissions.canJoinAutomatically)
            assertTrue(hostStore.outgoingRequests().isEmpty())
            assertEquals(1, relationshipsChanged)
        }

    @Test
    fun `authenticated removal converges locally and is idempotent`() = runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!.payload.peerId
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        hostStore.accept(senderCard, "bob", NOW)
        val admission = admission()
        val authenticated = AdmissionIdentity.Authenticated(
            name = "bob",
            uuid = PLAYER_UUID,
            source = AuthSource.MOJANG,
            ingress = Ingress.DIRECT_LAN,
            directPeerId = senderPeerId,
        )
        val approval = async {
            admission.request(authenticated)
        }
        runCurrent()
        admission.answer(admission.pending.value.single().requestId, true)
        assertEquals(AdmissionAnswer.ALLOW, approval.await())
        admission.approveNextJoin(
            AdmissionIdentity.UnverifiedOffline(
                name = "bob",
                uuid = PLAYER_UUID,
                connectionId = "friend-join",
                ingress = Ingress.DIRECT_LAN,
                directPeerId = senderPeerId,
            ),
        )
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission,
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            now = { NOW },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val context = FriendControlContext(
            ingress = Ingress.DIRECT_LAN,
            directPeerId = senderPeerId,
        )
        val removal = FriendRemovalRequest(UUID.randomUUID())

        assertEquals(
            FriendControlResponse.Removed,
            server.handleRemoval(context, removal).await(),
        )
        assertEquals(
            FriendControlResponse.Removed,
            server.handleRemoval(context, removal).await(),
        )
        assertTrue(hostStore.all().isEmpty())
        assertTrue(hostStore.pendingRemovals().isEmpty())
        val afterRemoval = async {
            admission.request(authenticated)
        }
        runCurrent()
        assertEquals(1, admission.pending.value.size)
        admission.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, afterRemoval.await())
    }

    @Test
    fun `removal never accepts Connect ingress`() = runTest {
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission(),
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            FriendControlResponse.Invalid,
            server.handleRemoval(
                FriendControlContext(Ingress.CONNECT, null),
                FriendRemovalRequest(UUID.randomUUID()),
            ).await(),
        )
    }

    @Test
    fun `confirmed friend can see server activity but not its address`() = runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!.payload.peerId
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        hostStore.accept(senderCard, "bob", NOW)
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission(),
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            activity = {
                FriendActivity(FriendActivityKind.PLAYING_SERVER, "Hypixel")
            },
            joinTarget = { "mc.hypixel.net" },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            FriendControlResponse.Activity(
                FriendActivity(FriendActivityKind.PLAYING_SERVER, "Hypixel"),
            ),
            server.handleActivity(
                FriendControlContext(Ingress.DIRECT_LAN, senderPeerId),
                FriendActivityRequest(UUID.randomUUID()),
            ).await(),
        )
    }

    @Test
    fun `join target is disclosed only after friend request is approved`() = runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!.payload.peerId
        val admission = admission()
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        hostStore.accept(senderCard, "bob", NOW)
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission,
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            activity = {
                FriendActivity(FriendActivityKind.PLAYING_SERVER, "Hypixel")
            },
            joinTarget = { "mc.hypixel.net" },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val response = server.handleJoin(
            FriendControlContext(Ingress.DIRECT_LAN, senderPeerId),
            FriendJoinRequest(
                UUID.randomUUID(),
                "RoboFlax2",
                PLAYER_UUID,
            ),
        ).toCompletableFuture()
        runCurrent()

        val pending = admission.pending.value.single()
        assertEquals(AdmissionPurpose.JOIN, pending.purpose)
        assertEquals("RoboFlax2", pending.identity.name)
        assertEquals(PLAYER_UUID, pending.identity.uuid)
        admission.answer(pending.requestId, allow = true)
        runCurrent()

        assertEquals(
            FriendControlResponse.JoinAccepted("mc.hypixel.net"),
            response.getNow(null),
        )
    }

    @Test
    fun `confirmed friend can request to join a shared singleplayer world`() = runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!.payload.peerId
        val admission = admission()
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        hostStore.accept(senderCard, "bob", NOW)
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission,
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            activity = {
                FriendActivity(
                    FriendActivityKind.HOSTING_WORLD,
                    "Survival",
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val response = server.handleJoin(
            FriendControlContext(Ingress.DIRECT_LAN, senderPeerId),
            FriendJoinRequest(
                UUID.randomUUID(),
                "RoboFlax2",
                PLAYER_UUID,
            ),
        ).toCompletableFuture()
        runCurrent()

        val pending = admission.pending.value.single()
        assertEquals(AdmissionPurpose.JOIN, pending.purpose)
        admission.answer(pending.requestId, allow = true)
        runCurrent()

        assertEquals(
            FriendControlResponse.SharedWorldJoinAccepted,
            response.getNow(null),
        )
    }

    @Test
    fun `never allow declines join without notifying the host`() = runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!.payload.peerId
        val admission = admission()
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        val friend = hostStore.accept(senderCard, "bob", NOW).getOrNull()!!
        hostStore.updatePermissions(
            senderPeerId,
            friend.permissions.copy(
                accessPolicy = FriendAccessPolicy.NEVER_ALLOW,
            ),
        )
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission,
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            activity = {
                FriendActivity(FriendActivityKind.HOSTING_WORLD, "Survival")
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val response = server.handleJoin(
            FriendControlContext(Ingress.DIRECT_LAN, senderPeerId),
            FriendJoinRequest(UUID.randomUUID(), "RoboFlax2", PLAYER_UUID),
        ).await()

        assertEquals(FriendControlResponse.Declined, response)
        assertTrue(admission.pending.value.isEmpty())
    }

    @Test
    fun `presence privacy can hide playing details without hiding online state`() = runTest {
        val senderCard = issuer("sender").issue(NOW).getOrNull()!!
        val senderPeerId = ShareInviteCodec.decode(senderCard, NOW)
            .getOrNull()!!.payload.peerId
        val hostStore = FriendStore(tempDir.resolve("host-store"))
        hostStore.accept(senderCard, "bob", NOW)
        val server = FriendRequestServer(
            scope = backgroundScope,
            admission = admission(),
            issuer = issuer("host"),
            receiver = FriendCardReceiver(hostStore),
            friendStore = hostStore,
            activity = {
                FriendActivity(FriendActivityKind.PLAYING_SERVER, "Private")
            },
            presencePrivacy = {
                PresencePrivacy(
                    showOnline = true,
                    showPlaying = false,
                    showCurrentServer = false,
                    showJoinable = false,
                )
            },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            FriendControlResponse.Activity(
                FriendActivity(FriendActivityKind.ONLINE),
            ),
            server.handleActivity(
                FriendControlContext(Ingress.DIRECT_LAN, senderPeerId),
                FriendActivityRequest(UUID.randomUUID()),
            ).await(),
        )
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
        val PLAYER_UUID: UUID =
            UUID.fromString("11111111-2222-3333-4444-555555555555")
    }
}
