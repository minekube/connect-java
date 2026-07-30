package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.Ingress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FabricLocalLoginAdmissionGateTest {
    @Test
    fun `exposes offline login approval as a cancellable Java stage`() = runTest {
        val admission = admission()
        val gate = FabricLocalLoginAdmissionGate(
            FabricLocalLoginAdmission(admission),
            backgroundScope,
        )

        val result = gate.request(
            name = "Alex",
            uuid = PLAYER_UUID,
            connectionId = "connection-1",
            minecraftAuthenticated = false,
            ingress = Ingress.DIRECT_LAN,
        ).toCompletableFuture()
        runCurrent()

        val pending = admission.pending.value.single()
        val identity = assertIs<AdmissionIdentity.UnverifiedOffline>(pending.identity)
        assertEquals("connection-1", identity.connectionId)
        assertEquals(Ingress.DIRECT_LAN, identity.ingress)
        admission.answer(pending.requestId, allow = true)
        runCurrent()

        assertEquals(AdmissionAnswer.ALLOW, result.getNow(null))
    }

    @Test
    fun `stop cancels pending and future login requests`() = runTest {
        val admission = admission()
        val gate = FabricLocalLoginAdmissionGate(
            FabricLocalLoginAdmission(admission),
            backgroundScope,
        )
        val pending = gate.request(
            name = "Alex",
            uuid = PLAYER_UUID,
            connectionId = "connection-1",
            minecraftAuthenticated = false,
        ).toCompletableFuture()
        runCurrent()

        gate.stop()
        runCurrent()
        val afterStop = gate.request(
            name = "Steve",
            uuid = UUID.randomUUID(),
            connectionId = "connection-2",
            minecraftAuthenticated = false,
        ).toCompletableFuture()

        assertTrue(pending.isCancelled)
        assertTrue(afterStop.isCancelled)
        admission.resetShare()
    }

    private fun kotlinx.coroutines.test.TestScope.admission() = AdmissionController(
        scope = backgroundScope,
        timeout = 30.seconds,
        maxPending = 16,
        connectedCount = { 0 },
        maxGuests = { 8 },
    )

    private companion object {
        val PLAYER_UUID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    }
}
