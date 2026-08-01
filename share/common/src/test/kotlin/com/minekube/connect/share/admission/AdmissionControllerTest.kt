package com.minekube.connect.share.admission

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdmissionControllerTest {
    @Test
    fun `cancelled request disappears immediately`() = runTest {
        val controller = controller()
        val request = async {
            controller.request(offline("Alex", "connection-cancelled"))
        }
        runCurrent()
        assertEquals(1, controller.pending.value.size)

        request.cancelAndJoin()
        runCurrent()

        assertTrue(controller.pending.value.isEmpty())
    }

    @Test
    fun `friend request bypasses world capacity and is labeled separately`() = runTest {
        val controller = controller(
            connectedCount = { 8 },
            maxGuests = { 8 },
        )
        val request = async {
            controller.request(
                offline("bob", "friend-request"),
                purpose = AdmissionPurpose.FRIEND,
            )
        }
        runCurrent()

        val pending = controller.pending.value.single()
        assertEquals(AdmissionPurpose.FRIEND, pending.purpose)
        controller.answer(pending.requestId, allow = false)
        assertEquals(AdmissionAnswer.DENY, request.await())
    }

    @Test
    fun `remote cancellation declines pending friend request for direct peer`() = runTest {
        val controller = controller()
        val request = async {
            controller.request(
                offline("bob", "friend-request").copy(
                    ingress = Ingress.DIRECT_LAN,
                    directPeerId = "12D3KooWFriend",
                ),
                purpose = AdmissionPurpose.FRIEND,
            )
        }
        runCurrent()

        assertEquals(
            1,
            controller.denyDirectPeer(
                "12D3KooWFriend",
                AdmissionPurpose.FRIEND,
            ),
        )
        assertEquals(AdmissionAnswer.DENY, request.await())
        assertTrue(controller.pending.value.isEmpty())
    }

    @Test
    fun `authenticated UUID approval is reused only during current share`() = runTest {
        val controller = controller()
        val identity = authenticated("Alex", AUTHENTICATED_UUID)
        val first = async { controller.request(identity) }
        runCurrent()

        controller.answer(controller.pending.value.single().requestId, allow = true)
        assertEquals(AdmissionAnswer.ALLOW, first.await())
        assertEquals(
            AdmissionAnswer.ALLOW,
            controller.request(identity.copy(name = "Renamed")),
        )

        controller.resetShare()
        val afterReset = async { controller.request(identity) }
        runCurrent()

        assertEquals(1, controller.pending.value.size)
        controller.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, afterReset.await())
    }

    @Test
    fun `offline reconnect with copied name requires a new approval`() = runTest {
        val controller = controller()
        val first = async {
            controller.request(offline("Alex", "connection-1"))
        }
        runCurrent()
        controller.answer(controller.pending.value.single().requestId, allow = true)
        assertEquals(AdmissionAnswer.ALLOW, first.await())

        val reconnect = async {
            controller.request(offline("Alex", "connection-2"))
        }
        runCurrent()

        val pendingIdentity = assertIs<AdmissionIdentity.UnverifiedOffline>(
            controller.pending.value.single().identity,
        )
        assertEquals("connection-2", pendingIdentity.connectionId)
        controller.answer(controller.pending.value.single().requestId, allow = false)
        assertEquals(AdmissionAnswer.DENY, reconnect.await())
    }

    @Test
    fun `duplicate live requests share one decision`() = runTest {
        val controller = controller()
        val identity = authenticated("Alex", AUTHENTICATED_UUID)
        val first = async { controller.request(identity) }
        val duplicate = async { controller.request(identity) }
        runCurrent()

        assertEquals(1, controller.pending.value.size)
        controller.answer(controller.pending.value.single().requestId, allow = true)

        assertEquals(AdmissionAnswer.ALLOW, first.await())
        assertEquals(AdmissionAnswer.ALLOW, duplicate.await())
    }

    @Test
    fun `seventeenth pending request is rejected`() = runTest {
        val controller = controller()
        val pending = (1..16).map { index ->
            async {
                controller.request(
                    offline("Guest$index", "connection-$index"),
                )
            }
        }
        runCurrent()

        val seventeenth = controller.request(
            offline("Guest17", "connection-17"),
        )

        assertEquals(AdmissionAnswer.CAPACITY, seventeenth)
        assertEquals(16, controller.pending.value.size)
        controller.resetShare()
        pending.forEach {
            assertEquals(AdmissionAnswer.STOPPED, it.await())
        }
    }

    @Test
    fun `request expires after thirty seconds`() = runTest {
        val controller = controller()
        val request = async {
            controller.request(offline("Alex", "connection-1"))
        }
        runCurrent()

        advanceTimeBy(29.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals(1, controller.pending.value.size)

        advanceTimeBy(1.seconds.inWholeMilliseconds)
        runCurrent()
        assertEquals(AdmissionAnswer.TIMEOUT, request.await())
        assertTrue(controller.pending.value.isEmpty())
    }

    @Test
    fun `stop resolves all pending requests and clears approvals`() = runTest {
        val controller = controller()
        val approved = authenticated("Alex", AUTHENTICATED_UUID)
        val approval = async { controller.request(approved) }
        runCurrent()
        controller.answer(controller.pending.value.single().requestId, allow = true)
        assertEquals(AdmissionAnswer.ALLOW, approval.await())

        val pending = async {
            controller.request(offline("Steve", "connection-1"))
        }
        runCurrent()
        controller.resetShare()

        assertEquals(AdmissionAnswer.STOPPED, pending.await())
        assertTrue(controller.pending.value.isEmpty())

        val approvalAfterStop = async { controller.request(approved) }
        runCurrent()
        assertEquals(1, controller.pending.value.size)
        controller.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, approvalAfterStop.await())
    }

    @Test
    fun `capacity rejects before adding a pending card`() = runTest {
        var connected = 8
        val controller = controller(
            connectedCount = { connected },
            maxGuests = { 8 },
        )

        val answer = controller.request(
            offline("Alex", "connection-1"),
        )

        assertEquals(AdmissionAnswer.CAPACITY, answer)
        assertTrue(controller.pending.value.isEmpty())

        connected = 0
        val pending = async {
            controller.request(offline("Alex", "connection-2"))
        }
        runCurrent()
        assertEquals(1, controller.pending.value.size)
        controller.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, pending.await())
    }

    @Test
    fun `saved direct peer can join automatically without a pending card`() = runTest {
        val controller = controller(
            autoApprove = { it.directPeerId == "12D3KooWSavedFriend" },
        )
        val saved = authenticated("Alex", AUTHENTICATED_UUID).copy(
            directPeerId = "12D3KooWSavedFriend",
            ingress = Ingress.DIRECT_LAN,
        )

        val answer = controller.request(saved)

        assertEquals(AdmissionAnswer.ALLOW, answer)
        assertTrue(controller.pending.value.isEmpty())

        val unknown = async {
            controller.request(
                saved.copy(directPeerId = "12D3KooWUnknownFriend"),
            )
        }
        runCurrent()
        assertEquals(1, controller.pending.value.size)
        controller.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, unknown.await())
    }

    @Test
    fun `approved friend request authorizes exactly one following gameplay join`() = runTest {
        val controller = controller()
        val requestedIdentity = offline("RoboFlax2", "friend-request").copy(
            directPeerId = "12D3KooWFriend",
            ingress = Ingress.DIRECT_LAN,
        )
        controller.approveNextJoin(requestedIdentity)

        assertEquals(
            AdmissionAnswer.ALLOW,
            controller.request(
                requestedIdentity.copy(connectionId = "gameplay-1"),
            ),
        )
        val second = async {
            controller.request(
                requestedIdentity.copy(connectionId = "gameplay-2"),
            )
        }
        runCurrent()

        assertEquals(1, controller.pending.value.size)
        controller.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, second.await())
    }

    @Test
    fun `approved friend request does not authorize another gameplay identity`() = runTest {
        val controller = controller()
        val requestedIdentity = offline("RoboFlax2", "friend-request").copy(
            uuid = AUTHENTICATED_UUID,
            directPeerId = "12D3KooWFriend",
            ingress = Ingress.DIRECT_LAN,
        )
        controller.approveNextJoin(requestedIdentity)

        val otherIdentity = async {
            controller.request(
                authenticated("RoboFlax2", AUTHENTICATED_UUID).copy(
                    directPeerId = "12D3KooWOtherFriend",
                ),
            )
        }
        runCurrent()

        assertEquals(1, controller.pending.value.size)
        controller.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, otherIdentity.await())
    }

    @Test
    fun `approved direct join allows the matching Connect fallback identity`() = runTest {
        val controller = controller()
        val requestedIdentity = offline("RoboFlax2", "friend-request").copy(
            uuid = AUTHENTICATED_UUID,
            directPeerId = "12D3KooWFriend",
            ingress = Ingress.DIRECT_LAN,
        )
        controller.approveNextJoin(requestedIdentity)

        assertEquals(
            AdmissionAnswer.ALLOW,
            controller.request(
                requestedIdentity.copy(
                    connectionId = "connect-gameplay",
                    directPeerId = null,
                    ingress = Ingress.CONNECT,
                ),
            ),
        )
    }

    @Test
    fun `removing a direct peer revokes every peer-scoped admission grant`() = runTest {
        val controller = controller()
        val peerId = "12D3KooWRemovedFriend"
        val authenticated = authenticated("Alex", AUTHENTICATED_UUID).copy(
            directPeerId = peerId,
        )
        val pending = async { controller.request(authenticated) }
        runCurrent()
        controller.answer(controller.pending.value.single().requestId, allow = true)
        assertEquals(AdmissionAnswer.ALLOW, pending.await())

        val offline = offline("Alex", "friend-request").copy(
            uuid = AUTHENTICATED_UUID,
            directPeerId = peerId,
        )
        controller.approveNextJoin(offline)

        assertEquals(0, controller.revokeDirectPeer(peerId))
        val revokedAuthenticated = async { controller.request(authenticated) }
        val revokedOffline = async {
            controller.request(offline.copy(connectionId = "gameplay"))
        }
        runCurrent()

        assertEquals(2, controller.pending.value.size)
        controller.resetShare()
        assertEquals(AdmissionAnswer.STOPPED, revokedAuthenticated.await())
        assertEquals(AdmissionAnswer.STOPPED, revokedOffline.await())
    }

    @Test
    fun `removing a linked peer denies uuid-bound pending Connect admission`() = runTest {
        val controller = controller()
        val pendingIdentity = authenticated("Alex", AUTHENTICATED_UUID).copy(
            directPeerId = null,
        )
        val pending = async { controller.request(pendingIdentity) }
        runCurrent()

        assertEquals(
            1,
            controller.revokeDirectPeer(
                peerId = "12D3KooWRemovedFriend",
                minecraftUuid = AUTHENTICATED_UUID,
            ),
        )
        assertEquals(AdmissionAnswer.DENY, pending.await())
        assertTrue(controller.pending.value.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        connectedCount: () -> Int = { 0 },
        maxGuests: () -> Int = { 8 },
        autoApprove: (AdmissionIdentity) -> Boolean = { false },
    ) = AdmissionController(
        scope = backgroundScope,
        timeout = 30.seconds,
        maxPending = 16,
        connectedCount = connectedCount,
        maxGuests = maxGuests,
        autoApprove = autoApprove,
    )

    private fun authenticated(
        name: String,
        uuid: UUID,
    ) = AdmissionIdentity.Authenticated(
        name = name,
        uuid = uuid,
        source = AuthSource.CONNECT,
    )

    private fun offline(
        name: String,
        connectionId: String,
    ) = AdmissionIdentity.UnverifiedOffline(
        name = name,
        uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()),
        connectionId = connectionId,
        ingress = Ingress.CONNECT,
    )

    private companion object {
        val AUTHENTICATED_UUID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    }
}
