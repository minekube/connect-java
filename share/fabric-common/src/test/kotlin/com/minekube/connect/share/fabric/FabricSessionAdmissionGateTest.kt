package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AuthSource
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.watch.SessionProposal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FabricSessionAdmissionGateTest {
    @Test
    fun `title control stays reachable while player sessions require a world`() =
        runTest {
            val admission = admission()
            var worldAvailable = false
            val gate = FabricSessionAdmissionGate(
                admission = admission,
                scope = backgroundScope,
                worldAvailable = { worldAvailable },
            )
            val unavailable = gate.request(
                proposal(passthrough = false),
            ).toCompletableFuture().getNow(null)

            assertFalse(unavailable.isAllowed)
            assertEquals(
                "No shared world is active",
                unavailable.safeMessage,
            )
            assertTrue(admission.pending.value.isEmpty())

            worldAvailable = true
            val available = gate.request(
                proposal(passthrough = false),
            ).toCompletableFuture()
            runCurrent()
            assertEquals(1, admission.pending.value.size)
            admission.answer(
                admission.pending.value.single().requestId,
                allow = false,
            )
            runCurrent()
            assertFalse(available.getNow(null).isAllowed)
        }

    @Test
    fun `status probe bypasses player admission for control routing`() = runTest {
        val admission = admission()
        val gate = FabricSessionAdmissionGate(admission, backgroundScope)
        val ping = Session.newBuilder()
            .setId("status-session")
            .setAuth(Authentication.newBuilder().setPassthrough(false))
            .setPlayer(
                Player.newBuilder()
                    .setAddr("127.0.0.1")
                    .setProfile(GameProfile.getDefaultInstance()),
            )
            .build()

        val decision = gate.request(SessionProposal(ping) {})
            .toCompletableFuture()
            .getNow(null)

        assertTrue(decision.isAllowed)
        assertTrue(admission.pending.value.isEmpty())
    }

    @Test
    fun `Connect authenticated profile waits for host approval`() = runTest {
        val admission = admission()
        val approvedJoins = ApprovedJoinTracker()
        val gate = FabricSessionAdmissionGate(
            admission,
            backgroundScope,
            approvedJoins,
        )

        val result = gate.request(proposal(passthrough = false)).toCompletableFuture()
        runCurrent()
        val pending = admission.pending.value.single()
        val identity = assertIs<AdmissionIdentity.Authenticated>(pending.identity)

        assertEquals("Alex", identity.name)
        assertEquals(PLAYER_UUID, identity.uuid)
        assertEquals(AuthSource.CONNECT, identity.source)
        admission.answer(pending.requestId, allow = true)
        runCurrent()
        assertTrue(result.getNow(null).isAllowed)
        assertFalse(
            approvedJoins.hasProof("Alex", PLAYER_UUID),
            "a Connect-only identity cannot authorize automatic friendship",
        )
    }

    @Test
    fun `passthrough profile defers approval to local login`() = runTest {
        val admission = admission()
        val gate = FabricSessionAdmissionGate(admission, backgroundScope)

        val result = gate.request(proposal(passthrough = true))
            .toCompletableFuture()
            .getNow(null)

        assertTrue(result.isDeferredToLocalLogin)
        assertTrue(admission.pending.value.isEmpty())
    }

    @Test
    fun `host denial becomes a safe Core denial`() = runTest {
        val admission = admission()
        val gate = FabricSessionAdmissionGate(admission, backgroundScope)
        val result = gate.request(proposal(passthrough = false)).toCompletableFuture()
        runCurrent()

        admission.answer(admission.pending.value.single().requestId, allow = false)
        runCurrent()

        val decision = result.getNow(null)
        assertFalse(decision.isAllowed)
        assertFalse(decision.isDeferredToLocalLogin)
        assertEquals(
            "The host declined this join. Request access again when ready.",
            decision.safeMessage,
        )
    }

    @Test
    fun `Connect approval timeout leaves time for an actionable disconnect`() =
        runTest {
            val admission = admission()
            val gate = FabricSessionAdmissionGate(
                admission = admission,
                scope = backgroundScope,
                decisionTimeout = 20.seconds,
            )
            val result = gate.request(proposal(passthrough = false))
                .toCompletableFuture()
            runCurrent()

            advanceTimeBy(19_999)
            assertFalse(result.isDone)
            advanceTimeBy(1)
            runCurrent()

            assertTrue(result.isDone)
            assertFalse(result.getNow(null).isAllowed)
            assertEquals(
                "The host did not approve this join in time. Try again.",
                result.getNow(null).safeMessage,
            )
            assertTrue(admission.pending.value.isEmpty())
        }

    @Test
    fun `stopping gate cancels pending Core stages`() = runTest {
        val admission = admission()
        val gate = FabricSessionAdmissionGate(admission, backgroundScope)
        val result = gate.request(proposal(passthrough = false)).toCompletableFuture()
        runCurrent()

        gate.stop()
        runCurrent()

        assertTrue(result.isCancelled)
        admission.resetShare()
    }

    @Test
    fun `malformed Connect profile is denied without pending approval`() = runTest {
        val admission = admission()
        val gate = FabricSessionAdmissionGate(admission, backgroundScope)
        val malformed = Session.newBuilder()
            .setAuth(Authentication.newBuilder().setPassthrough(false))
            .setPlayer(
                Player.newBuilder()
                    .setAddr("127.0.0.1")
                    .setProfile(
                        GameProfile.newBuilder()
                            .setName("Alex")
                            .setId("not-a-uuid"),
                    ),
            )
            .build()

        val decision = gate.request(SessionProposal(malformed) {}).toCompletableFuture()
            .getNow(null)

        assertFalse(decision.isAllowed)
        assertEquals("Connect profile is invalid", decision.safeMessage)
        assertTrue(admission.pending.value.isEmpty())
    }

    @Test
    fun `local login maps authenticated and offline identities separately`() = runTest {
        val admission = admission()
        val approvedJoins = ApprovedJoinTracker()
        val local = FabricLocalLoginAdmission(
            admission,
            approvedJoins,
        )
        val authenticated = async {
            local.request(
                name = "Alex",
                uuid = PLAYER_UUID,
                connectionId = "connection-authenticated",
                minecraftAuthenticated = true,
                directPeerId = "12D3KooWAuthenticated",
            )
        }
        runCurrent()
        val authenticatedIdentity = assertIs<AdmissionIdentity.Authenticated>(
            admission.pending.value.single().identity,
        )
        assertEquals(AuthSource.MOJANG, authenticatedIdentity.source)
        assertEquals(
            "12D3KooWAuthenticated",
            authenticatedIdentity.directPeerId,
        )
        admission.answer(admission.pending.value.single().requestId, allow = true)
        assertEquals(AdmissionAnswer.ALLOW, authenticated.await())
        assertTrue(approvedJoins.hasProof("Alex", PLAYER_UUID))

        val offline = async {
            local.request(
                name = "Alex",
                uuid = PLAYER_UUID,
                connectionId = "connection-offline",
                minecraftAuthenticated = false,
                directPeerId = "12D3KooWOffline",
            )
        }
        runCurrent()
        val offlineIdentity = assertIs<AdmissionIdentity.UnverifiedOffline>(
            admission.pending.value.single().identity,
        )
        assertEquals("connection-offline", offlineIdentity.connectionId)
        assertEquals(Ingress.CONNECT, offlineIdentity.ingress)
        assertEquals("12D3KooWOffline", offlineIdentity.directPeerId)
        admission.answer(admission.pending.value.single().requestId, allow = false)
        assertEquals(AdmissionAnswer.DENY, offline.await())
    }

    private fun kotlinx.coroutines.test.TestScope.admission() = AdmissionController(
        scope = backgroundScope,
        timeout = 30.seconds,
        maxPending = 16,
        connectedCount = { 0 },
        maxGuests = { 8 },
    )

    private fun proposal(passthrough: Boolean): SessionProposal {
        val session = Session.newBuilder()
            .setId("session-1")
            .setAuth(Authentication.newBuilder().setPassthrough(passthrough))
            .setPlayer(
                Player.newBuilder()
                    .setAddr("127.0.0.1")
                    .setProfile(
                        GameProfile.newBuilder()
                            .setName("Alex")
                            .setId(PLAYER_UUID.toString()),
                    ),
            )
            .build()
        return SessionProposal(session) {}
    }

    private companion object {
        val PLAYER_UUID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    }
}
