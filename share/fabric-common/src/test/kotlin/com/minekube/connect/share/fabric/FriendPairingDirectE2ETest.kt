package com.minekube.connect.share.fabric

import com.minekube.connect.share.ShareConnectionGateway
import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.DirectSessionRegistry
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendRemovalRequest
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendActivityRequest
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.ShareAccessIdentityStore
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pHostConfig
import com.minekube.connect.tunnel.p2p.DirectP2pHostHandler
import com.minekube.connect.tunnel.p2p.DirectP2pHostInfo
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import com.minekube.connect.tunnel.p2p.DirectP2pSession
import com.minekube.connect.tunnel.p2p.Libp2pRuntime
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

class FriendPairingDirectE2ETest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `signed friend request traverses a real direct libp2p proxy`() =
        runBlocking {
            val now = Instant.parse("2026-07-31T12:00:00Z")
            val hostDirectory = tempDir.resolve("host")
            val senderDirectory = tempDir.resolve("sender")
            val hostStore = FriendStore(hostDirectory)
            val senderStore = FriendStore(senderDirectory)
            val admission = AdmissionController(
                scope = this,
                timeout = 10.seconds,
                maxPending = 8,
                connectedCount = { 0 },
                maxGuests = { 8 },
            )
            val hostActivity = AtomicReference(
                FriendActivity(
                    FriendActivityKind.PLAYING_SERVER,
                    "Hypixel",
                ),
            )
            val hostServer = FriendRequestServer(
                scope = this,
                admission = admission,
                issuer = FriendCardIssuer(hostDirectory) {
                    "host.play.minekube.net"
                },
                receiver = FriendCardReceiver(hostStore),
                friendStore = hostStore,
                now = { now },
                ioDispatcher = Dispatchers.IO,
                activity = hostActivity::get,
                joinTarget = { "mc.hypixel.net" },
            )

            try {
                ShareConnectionGateway.bind(hostServer).use { gateway ->
                    val access = ShareAccessIdentityStore(
                        hostDirectory,
                    ).currentOrCreate()
                    val hostNode = DirectP2pNode(
                        hostDirectory.resolve(IDENTITY_FILE_NAME),
                    )
                    val hostInfo = AtomicReference<DirectP2pHostInfo>()
                    val directIngress = FabricDirectShareIngress.testing(
                        nodeFactory = {
                            RealHostNode(hostNode, hostInfo)
                        },
                        now = { now },
                        shareId = { access.shareId },
                        capability = { access.capability },
                        displayName = { "Host control plane" },
                        localSocket = ::openTaggedGatewaySocket,
                    )
                    val direct = directIngress.start(
                        options = OPTIONS,
                        target = gateway.directAddress,
                        connectAddress = "host.play.minekube.net",
                    )
                    val browser = FabricShareBrowser.testing(
                        node = RealGuestNode(
                            DirectP2pNode(
                                senderDirectory.resolve(
                                    IDENTITY_FILE_NAME,
                                ),
                            ),
                        ),
                        now = { now },
                        ioDispatcher = Dispatchers.IO,
                    )
                    try {
                        val requestClient = FriendRequestClient(
                            ioDispatcher = Dispatchers.IO,
                            connectTimeout = Duration.ofSeconds(3),
                            decisionTimeout = Duration.ofSeconds(5),
                        )
                        val pairing = FriendPairingClient(
                            store = senderStore,
                            issuer = FriendCardIssuer(senderDirectory) {
                                "sender.play.minekube.net"
                            },
                            receiver = FriendCardReceiver(senderStore),
                            requestClient = requestClient,
                            now = { now },
                            ioDispatcher = Dispatchers.IO,
                        )
                        var received = false
                        val result = async {
                            pairing.send(
                                invitation = direct.invitation,
                                friendDisplayName = "RoboFlax2",
                                senderDisplayName = "bob",
                                route = {
                                    browser.join(
                                        invitationUri =
                                            direct.invitation,
                                        lanAddress = hostInfo.get()
                                            .lanAddresses()
                                            .first(),
                                        internetOptIn = false,
                                        authMode =
                                            DirectP2pAuthMode.OFFLINE,
                                    )
                                },
                                onReceived = { received = true },
                            )
                        }

                        val pending = withTimeout(5.seconds) {
                            admission.pending
                                .first { it.isNotEmpty() }
                                .single()
                        }
                        assertTrue(received)
                        admission.answer(pending.requestId, allow = true)

                        assertTrue(result.await().isRight())
                        assertEquals(
                            "bob",
                            hostStore.all().single().displayName,
                        )
                        assertEquals(
                            "RoboFlax2",
                            senderStore.all().single().displayName,
                        )

                        val activityTarget = browser.join(
                            invitationUri = direct.invitation,
                            lanAddress = hostInfo.get().lanAddresses().first(),
                            internetOptIn = false,
                            authMode = DirectP2pAuthMode.OFFLINE,
                        ).getOrNull() as GuestJoinTarget.Direct
                        assertEquals(
                            FriendActivity(
                                FriendActivityKind.PLAYING_SERVER,
                                "Hypixel",
                            ),
                            requestClient.activity(
                                activityTarget,
                                FriendActivityRequest(java.util.UUID.randomUUID()),
                            ).getOrNull(),
                        )

                        val joinTarget = browser.join(
                            invitationUri = direct.invitation,
                            lanAddress = hostInfo.get().lanAddresses().first(),
                            internetOptIn = false,
                            authMode = DirectP2pAuthMode.OFFLINE,
                        ).getOrNull() as GuestJoinTarget.Direct
                        val requestedJoin = async {
                            requestClient.requestJoin(
                                joinTarget,
                                FriendJoinRequest(
                                    java.util.UUID.randomUUID(),
                                    "bob",
                                    java.util.UUID.randomUUID(),
                                ),
                            )
                        }
                        val joinAdmission = withTimeout(5.seconds) {
                            admission.pending.first {
                                it.singleOrNull()?.purpose ==
                                    com.minekube.connect.share.admission.AdmissionPurpose.JOIN
                            }.single()
                        }
                        admission.answer(joinAdmission.requestId, allow = true)
                        assertEquals(
                            FriendJoinApproval.ExternalServer("mc.hypixel.net"),
                            requestedJoin.await().getOrNull(),
                        )

                        hostActivity.set(
                            FriendActivity(
                                FriendActivityKind.HOSTING_WORLD,
                                "Survival",
                            ),
                        )
                        val playerUuid = java.util.UUID.randomUUID()
                        val worldJoinTarget = browser.join(
                            invitationUri = direct.invitation,
                            lanAddress = hostInfo.get().lanAddresses().first(),
                            internetOptIn = false,
                            authMode = DirectP2pAuthMode.OFFLINE,
                        ).getOrNull() as GuestJoinTarget.Direct
                        val requestedWorldJoin = async {
                            requestClient.requestJoin(
                                worldJoinTarget,
                                FriendJoinRequest(
                                    java.util.UUID.randomUUID(),
                                    "bob",
                                    playerUuid,
                                ),
                            )
                        }
                        val worldAdmission = withTimeout(5.seconds) {
                            admission.pending.first {
                                it.singleOrNull()?.purpose ==
                                    AdmissionPurpose.JOIN
                            }.single()
                        }
                        admission.answer(worldAdmission.requestId, allow = true)
                        assertEquals(
                            FriendJoinApproval.SharedWorld,
                            requestedWorldJoin.await().getOrNull(),
                        )
                        assertEquals(
                            AdmissionAnswer.ALLOW,
                            admission.request(
                                AdmissionIdentity.UnverifiedOffline(
                                    name = "bob",
                                    uuid = playerUuid,
                                    connectionId = "connect-gameplay",
                                    ingress = Ingress.CONNECT,
                                ),
                                AdmissionPurpose.JOIN,
                            ),
                        )
                        assertTrue(admission.pending.value.isEmpty())

                        val hostPeerId = senderStore.all().single().peerId
                        assertTrue(senderStore.remove(hostPeerId, now))
                        val removal = senderStore.pendingRemovals().single()
                        val removalTarget = browser.join(
                            invitationUri = direct.invitation,
                            lanAddress = hostInfo.get().lanAddresses().first(),
                            internetOptIn = false,
                            authMode = DirectP2pAuthMode.OFFLINE,
                        ).getOrNull() as GuestJoinTarget.Direct

                        assertTrue(
                            requestClient.remove(
                                removalTarget,
                                FriendRemovalRequest(
                                    operationId = removal.operationId,
                                    relationshipId = removal.friend.relationshipId,
                                ),
                            ).isRight(),
                        )
                        senderStore.acknowledgeRemoval(removal.operationId)

                        assertTrue(hostStore.all().isEmpty())
                        assertTrue(senderStore.all().isEmpty())
                        assertTrue(senderStore.pendingRemovals().isEmpty())
                    } finally {
                        browser.close()
                        direct.close()
                    }
                }
            } finally {
                Libp2pRuntime.close()
            }
        }

    private class RealHostNode(
        private val node: DirectP2pNode,
        private val hostInfo: AtomicReference<DirectP2pHostInfo>,
    ) : FabricDirectNode {
        override fun startHost(
            config: DirectP2pHostConfig,
            handler: DirectP2pHostHandler,
        ): DirectP2pHostInfo = node.startHost(config, handler).also(
            hostInfo::set,
        )

        override fun sign(payload: ByteArray): ByteArray =
            node.sign(payload)

        override fun publish(
            invitation: String,
            discoveryInvitation: String,
        ) {
            node.publish(invitation, discoveryInvitation)
        }

        override fun close() {
            node.close()
        }
    }

    private class RealGuestNode(
        private val node: DirectP2pNode,
    ) : FabricGuestDirectNode {
        override fun peerId(): String = node.peerId()

        override fun startDiscovery(
            listener: DirectP2pDiscoveryListener,
        ) {
            node.startDiscovery(listener)
        }

        override fun openProxy(
            address: String,
            shareId: String,
            capability: String,
            authMode: DirectP2pAuthMode,
            timeout: Duration,
        ): DirectP2pProxy = node.openProxy(
            address,
            shareId,
            capability,
            authMode,
            timeout,
        )

        override fun close() {
            node.close()
        }
    }

    private fun openTaggedGatewaySocket(
        target: SocketAddress,
        session: DirectP2pSession,
    ): Socket {
        val socket = Socket()
        socket.bind(
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        )
        val registration = DirectSessionRegistry.register(
            sourcePort = socket.localPort,
            session = session,
        )
        return try {
            socket.connect(target)
            socket
        } catch (failure: Throwable) {
            registration.close()
            socket.close()
            throw failure
        }
    }

    private companion object {
        const val IDENTITY_FILE_NAME =
            "share-libp2p-identity.key"
        val OPTIONS = ShareOptions(
            gameMode = ShareGameMode.SURVIVAL,
            allowCheats = false,
            allowInternetDirect = false,
        )
    }
}
