package com.minekube.connect.share.fabric

import arrow.core.right
import com.minekube.connect.share.ShareConnectionGateway
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.friend.FriendRelationshipStatus
import com.minekube.connect.share.friend.FriendStore
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

class FriendPairingE2ETest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `signed request accepted through title gateway persists mutual friendship`() =
        runBlocking {
            val now = Instant.parse("2026-07-31T10:30:00Z")
            val hostDirectory = tempDir.resolve("host")
            val senderDirectory = tempDir.resolve("sender")
            val hostStore = FriendStore(hostDirectory)
            val senderStore = FriendStore(senderDirectory)
            val hostAddress = AtomicReference<String>()
            var hostRelationshipsChanged = 0
            val hostIssuer = FriendCardIssuer(hostDirectory) {
                hostAddress.get()
            }
            val admission = AdmissionController(
                scope = this,
                timeout = 10.seconds,
                maxPending = 8,
                connectedCount = { 0 },
                maxGuests = { 8 },
            )
            val hostServer = FriendRequestServer(
                scope = this,
                admission = admission,
                issuer = hostIssuer,
                receiver = FriendCardReceiver(hostStore),
                friendStore = hostStore,
                now = { now },
                ioDispatcher = Dispatchers.IO,
                onRelationshipChanged = {
                    hostRelationshipsChanged++
                },
            )
            ShareConnectionGateway.bind(hostServer).use { gateway ->
                hostAddress.set(
                    "${gateway.directAddress.hostString}:" +
                        gateway.directAddress.port,
                )
                val invitation = hostIssuer.issue(now).getOrNull()!!
                val pairing = FriendPairingClient(
                    store = senderStore,
                    issuer = FriendCardIssuer(senderDirectory) {
                        "sender.play.minekube.net"
                    },
                    receiver = FriendCardReceiver(senderStore),
                    requestClient = FriendRequestClient(
                        protocolVersion = 1_075,
                        ioDispatcher = Dispatchers.IO,
                        connectTimeout = Duration.ofSeconds(2),
                        decisionTimeout = Duration.ofSeconds(5),
                    ),
                    now = { now },
                    ioDispatcher = Dispatchers.IO,
                )
                var received = false

                val result = async {
                    pairing.send(
                        invitation = invitation,
                        friendDisplayName = "RoboFlax2",
                        senderDisplayName = "bob",
                        route = { saved ->
                            GuestJoinTarget.Connect(
                                checkNotNull(saved.connectAddress),
                            ).right()
                        },
                        onReceived = { received = true },
                    )
                }

                val pending = withTimeout(2.seconds) {
                    admission.pending.first { it.isNotEmpty() }.single()
                }
                assertTrue(received)
                assertTrue(hostStore.all().isEmpty())
                assertEquals(
                    FriendRelationshipStatus.PENDING_OUTGOING,
                    senderStore.outgoingRequests()
                        .single()
                        .relationshipStatus,
                )

                admission.answer(pending.requestId, allow = true)
                val accepted = result.await().getOrNull()!!

                assertEquals(
                    FriendRelationshipStatus.CONFIRMED,
                    accepted.relationshipStatus,
                )
                assertTrue(senderStore.outgoingRequests().isEmpty())
                assertEquals("RoboFlax2", senderStore.all().single().displayName)
                assertEquals("bob", hostStore.all().single().displayName)
                assertEquals(1, hostRelationshipsChanged)
                assertTrue(
                    senderStore.all()
                        .single()
                        .permissions
                        .canJoinAutomatically,
                )
                assertTrue(
                    hostStore.all()
                        .single()
                        .permissions
                        .canJoinAutomatically,
                )
                assertFalse(
                    senderStore.all().single().peerId ==
                        hostStore.all().single().peerId,
                )
            }
        }
}
