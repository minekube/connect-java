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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FabricSessionAdmissionGateTest {
    @Test
    fun `Connect authenticated profile waits for host approval`() = runTest {
        val admission = admission()
        val gate = FabricSessionAdmissionGate(admission, backgroundScope)

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
        assertEquals("Host denied this connection", decision.safeMessage)
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
        val local = FabricLocalLoginAdmission(admission)
        val authenticated = async {
            local.request(
                name = "Alex",
                uuid = PLAYER_UUID,
                connectionId = "connection-authenticated",
                minecraftAuthenticated = true,
            )
        }
        runCurrent()
        val authenticatedIdentity = assertIs<AdmissionIdentity.Authenticated>(
            admission.pending.value.single().identity,
        )
        assertEquals(AuthSource.MOJANG, authenticatedIdentity.source)
        admission.answer(admission.pending.value.single().requestId, allow = true)
        assertEquals(AdmissionAnswer.ALLOW, authenticated.await())

        val offline = async {
            local.request(
                name = "Alex",
                uuid = PLAYER_UUID,
                connectionId = "connection-offline",
                minecraftAuthenticated = false,
            )
        }
        runCurrent()
        val offlineIdentity = assertIs<AdmissionIdentity.UnverifiedOffline>(
            admission.pending.value.single().identity,
        )
        assertEquals("connection-offline", offlineIdentity.connectionId)
        assertEquals(Ingress.CONNECT, offlineIdentity.ingress)
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
