package com.minekube.connect.share

import arrow.core.Either
import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.EndpointIdentity
import java.net.InetSocketAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShareCoordinatorTest {
    @Test
    fun `start orders bridge before ingress`() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(
            events = events,
            identityProvider = {
                events += "identity"
                IDENTITY
            },
        )

        val result = fixture.coordinator.start(OPTIONS)

        val sharing = assertIs<Either.Right<ShareState.Sharing>>(result).value
        assertEquals(
            listOf("bridge-open", "identity", "ingress-start"),
            events,
        )
        assertEquals("amber-fox", sharing.endpoint)
        assertEquals("amber-fox.play.minekube.net", sharing.address)
        assertEquals(sharing, fixture.coordinator.state.value)
    }

    @Test
    fun `connect failure closes bridge and enters failed`() = runTest {
        val events = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val fixture = fixture(
            events = events,
            failureReporter = reports::add,
            ingressStart = { _, _ ->
                events += "ingress-start"
                error("T-secret")
            },
        )

        val result = fixture.coordinator.start(OPTIONS)

        assertIs<Either.Left<ShareLifecycleError.StartFailed>>(result)
        val failed = assertIs<ShareState.Failed>(fixture.coordinator.state.value)
        assertEquals(listOf("bridge-open", "ingress-start", "bridge-close"), events)
        assertFalse(failed.safeMessage.contains("T-secret"))
        assertTrue(reports.single().contains("start", ignoreCase = true))
        assertFalse(reports.single().contains("T-secret"))
    }

    @Test
    fun `stop closes ingress then bridge and clears admission`() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        assertIs<Either.Right<ShareState.Sharing>>(
            fixture.coordinator.start(OPTIONS),
        )
        val waiting = async {
            fixture.admission.request(
                AdmissionIdentity.UnverifiedOffline(
                    name = "Alex",
                    uuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                    connectionId = "connection-1",
                    ingress = Ingress.CONNECT,
                ),
            )
        }
        runCurrent()

        val result = fixture.coordinator.stop()

        assertIs<Either.Right<Unit>>(result)
        assertEquals(
            listOf(
                "bridge-open",
                "ingress-start",
                "ingress-close",
                "bridge-close",
            ),
            events,
        )
        assertEquals(AdmissionAnswer.STOPPED, waiting.await())
        assertTrue(fixture.admission.pending.value.isEmpty())
        assertEquals(ShareState.Idle, fixture.coordinator.state.value)
    }

    @Test
    fun `stop attempts every release when ingress close fails`() = runTest {
        val events = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val fixture = fixture(
            events = events,
            failureReporter = reports::add,
            ingressClose = {
                events += "ingress-close"
                error("T-cleanup-secret")
            },
        )
        fixture.coordinator.start(OPTIONS)

        val result = fixture.coordinator.stop()

        assertIs<Either.Left<ShareLifecycleError.StopFailed>>(result)
        assertTrue(events.indexOf("bridge-close") > events.indexOf("ingress-close"))
        assertEquals(ShareState.Idle, fixture.coordinator.state.value)
        assertFalse(reports.single().contains("T-cleanup-secret"))
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.coordinator.start(OPTIONS)

        fixture.coordinator.stop()
        fixture.coordinator.stop()

        assertEquals(1, events.count { it == "ingress-close" })
        assertEquals(1, events.count { it == "bridge-close" })
        assertEquals(ShareState.Idle, fixture.coordinator.state.value)
    }

    @Test
    fun `world replacement stops active share`() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(events)
        fixture.coordinator.start(OPTIONS)

        fixture.coordinator.worldReplaced()

        assertEquals(1, events.count { it == "ingress-close" })
        assertEquals(1, events.count { it == "bridge-close" })
        assertEquals(ShareState.Idle, fixture.coordinator.state.value)
    }

    @Test
    fun `capacity outside one through sixteen is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ShareOptions(
                gameMode = ShareGameMode.SURVIVAL,
                allowCheats = false,
                maxGuests = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ShareOptions(
                gameMode = ShareGameMode.SURVIVAL,
                allowCheats = false,
                maxGuests = 17,
            )
        }
    }

    @Test
    fun `start cancellation releases the bridge and remains cancellation`() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(
            events = events,
            identityProvider = {
                awaitCancellation()
            },
        )
        val starting = launch {
            fixture.coordinator.start(OPTIONS)
        }
        runCurrent()

        starting.cancelAndJoin()

        assertEquals(listOf("bridge-open", "bridge-close"), events)
        assertEquals(ShareState.Idle, fixture.coordinator.state.value)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        events: MutableList<String>,
        identityProvider: suspend () -> EndpointIdentity = { IDENTITY },
        ingressStart: suspend (
            EndpointIdentity,
            java.net.SocketAddress,
        ) -> ConnectShareHandle = { identity, _ ->
            events += "ingress-start"
            ConnectShareHandle(
                endpoint = identity.endpoint,
                publicAddress = "${identity.endpoint}.play.minekube.net",
                close = {
                    events += "ingress-close"
                },
            )
        },
        ingressClose: suspend () -> Unit = {
            events += "ingress-close"
        },
        failureReporter: (String) -> Unit = {},
    ): Fixture {
        val admission = AdmissionController(
            scope = backgroundScope,
            timeout = 30.seconds,
            maxPending = 16,
            connectedCount = { 0 },
            maxGuests = { OPTIONS.maxGuests },
        )
        val bridge = MinecraftShareBridge {
            events += "bridge-open"
            LocalShareTarget(
                address = InetSocketAddress.createUnresolved("127.0.0.1", 25565),
                close = {
                    events += "bridge-close"
                },
            )
        }
        val ingress = ConnectShareIngress { identity, target ->
            val handle = ingressStart(identity, target)
            handle.copy(close = ingressClose)
        }
        return Fixture(
            coordinator = ShareCoordinator(
                bridge = bridge,
                ingress = ingress,
                identityProvider = identityProvider,
                admission = admission,
                failureReporter = failureReporter,
            ),
            admission = admission,
        )
    }

    private data class Fixture(
        val coordinator: ShareCoordinator,
        val admission: AdmissionController,
    )

    private companion object {
        val OPTIONS = ShareOptions(
            gameMode = ShareGameMode.SURVIVAL,
            allowCheats = false,
            maxGuests = 8,
        )
        val IDENTITY = EndpointIdentity(
            endpoint = "amber-fox",
            token = "T-AAAAAAAAAAAAAAAAAAAA",
            endpointSource = CredentialSource.GENERATED,
            tokenSource = CredentialSource.GENERATED,
        )
    }
}
