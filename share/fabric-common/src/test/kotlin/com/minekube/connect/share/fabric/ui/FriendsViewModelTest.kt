package com.minekube.connect.share.fabric.ui

import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.admission.PendingAdmission
import com.minekube.connect.share.fabric.DiscoveredLanShare
import com.minekube.connect.share.fabric.FabricGuestDirectNode
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.GuestJoinFailure
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.fabric.RemoteFriendPresence
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveredShare
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FriendsViewModelTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `sending one link exposes only an outgoing request`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))

        assertEquals(
            PEER_ID,
            viewModel.sendRequest(signedLink(), "Robin", NOW),
        )

        val request = viewModel.state.value.outgoingRequests.single()
        assertEquals(PEER_ID, request.peerId)
        assertEquals("Robin", request.displayName)
        assertTrue(viewModel.state.value.friends.isEmpty())
        assertFalse(viewModel.state.value.toString().contains(CAPABILITY))
        assertEquals(null, viewModel.state.value.safeMessage)
    }

    @Test
    fun `signed friend link suggests its sender username`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))

        val suggested = viewModel.suggestedDisplayName(
            signedLink(displayName = "RoboFlax2"),
            NOW,
        )

        assertEquals("RoboFlax2", suggested.getOrNull())
    }

    @Test
    fun `outgoing request never exposes presence as a friend`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.sendRequest(signedLink(), "Robin", NOW)

        viewModel.updateRemotePresence(
            mapOf(
                PEER_ID to RemoteFriendPresence(
                    peerId = PEER_ID,
                    displayName = "Robin",
                    online = true,
                    description = "Robin's World",
                    notifyWhenOnline = true,
                ),
            ),
        )

        assertTrue(viewModel.state.value.friends.isEmpty())
        assertEquals(
            PEER_ID,
            viewModel.state.value.outgoingRequests.single().peerId,
        )
    }

    @Test
    fun `friends state exposes both friend and join approvals`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        val friendRequestId = UUID.randomUUID()
        val joinRequestId = UUID.randomUUID()

        viewModel.updateIncoming(
            listOf(
                PendingAdmission(
                    requestId = friendRequestId,
                    identity = AdmissionIdentity.UnverifiedOffline(
                        name = "bob",
                        uuid = UUID.randomUUID(),
                        connectionId = "friend:bob",
                        ingress = Ingress.CONNECT,
                    ),
                    purpose = AdmissionPurpose.FRIEND,
                ),
                PendingAdmission(
                    requestId = joinRequestId,
                    identity = AdmissionIdentity.UnverifiedOffline(
                        name = "visitor",
                        uuid = UUID.randomUUID(),
                        connectionId = "join:visitor",
                        ingress = Ingress.DIRECT_LAN,
                    ),
                    purpose = AdmissionPurpose.JOIN,
                ),
            ),
        )

        val incoming = viewModel.state.value.incomingRequests
        assertEquals(2, incoming.size)
        assertEquals(friendRequestId, incoming[0].requestId)
        assertEquals(AdmissionPurpose.FRIEND, incoming[0].purpose)
        assertEquals("bob", incoming[0].displayName)
        assertEquals(Ingress.CONNECT, incoming[0].ingress)
        assertEquals(joinRequestId, incoming[1].requestId)
        assertEquals(AdmissionPurpose.JOIN, incoming[1].purpose)
        assertTrue(viewModel.state.value.friends.isEmpty())
        assertTrue(viewModel.state.value.outgoingRequests.isEmpty())
    }

    @Test
    fun `unchanged incoming tick observes an accepted relationship`() {
        val store = FriendStore(tempDir)
        val viewModel = FriendsViewModel(store)
        viewModel.updateIncoming(emptyList())

        store.accept(signedLink(), "Robin", NOW)
        viewModel.updateIncoming(emptyList())

        assertEquals("Robin", viewModel.state.value.friends.single().displayName)
        assertTrue(viewModel.state.value.incomingRequests.isEmpty())
    }

    @Test
    fun `invalid friend link stays on the add flow with a useful message`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))

        val accepted = viewModel.sendRequest(
            "minekube://share/not-a-valid-link",
            "Robin",
            NOW,
        )

        assertEquals(null, accepted)
        assertTrue(viewModel.state.value.friends.isEmpty())
        assertTrue(viewModel.state.value.safeMessage?.isNotBlank() == true)
    }

    @Test
    fun `unchanged presence ticks do not erase an operation error`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.sendRequest(
            "minekube://share/not-a-valid-link",
            "Robin",
            NOW,
        )
        val message = viewModel.state.value.safeMessage

        repeat(20) {
            viewModel.updatePresence(emptyList())
            viewModel.updateRemotePresence(emptyMap())
        }

        assertEquals(message, viewModel.state.value.safeMessage)
    }

    @Test
    fun `saved friend can be renamed configured and removed`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        val viewModel = FriendsViewModel(store)

        viewModel.rename(PEER_ID, "Robin from Discord")
        viewModel.updatePermissions(
            PEER_ID,
            FriendPermissions(
                notifyWhenOnline = false,
                canSeeMyWorlds = true,
                canJoinAutomatically = true,
            ),
        )

        val managed = viewModel.state.value.friends.single()
        assertEquals("Robin from Discord", managed.displayName)
        assertFalse(managed.permissions.notifyWhenOnline)
        assertTrue(managed.permissions.canJoinAutomatically)

        assertTrue(viewModel.remove(PEER_ID))

        assertTrue(viewModel.state.value.friends.isEmpty())
        assertTrue(FriendStore(tempDir).all().isEmpty())
        assertFalse(viewModel.remove(PEER_ID))
    }

    @Test
    fun `matching discovery marks a saved friend world ready to join`() {
        val link = signedLink()
        val invitation = ShareInviteCodec.decode(link, NOW).getOrNull()!!
        val store = FriendStore(tempDir)
        store.accept(link, "Robin", NOW)
        val viewModel = FriendsViewModel(store)

        viewModel.updatePresence(
            listOf(
                DiscoveredLanShare(
                    displayName = "Robin's New World",
                    invitationUri = link,
                    invitation = invitation,
                    lanAddress =
                        "/ip4/192.168.1.25/tcp/4001/p2p/$PEER_ID",
                ),
            ),
        )
        viewModel.updateRemotePresence(
            mapOf(
                PEER_ID to RemoteFriendPresence(
                    peerId = PEER_ID,
                    displayName = "Robin",
                    online = true,
                    description = "Robin's New World",
                    notifyWhenOnline = true,
                    route = ShareRoute.DIRECT_LAN,
                ),
            ),
        )

        val online = viewModel.state.value.friends.single()
        assertTrue(online.onlineViaLan)
        assertEquals("Robin's New World", online.worldName)

        viewModel.updatePresence(emptyList())
        viewModel.updateRemotePresence(emptyMap())

        assertFalse(viewModel.state.value.friends.single().onlineViaLan)
    }

    @Test
    fun `cancelling an outgoing request removes only pending state`() {
        val store = FriendStore(tempDir)
        val viewModel = FriendsViewModel(store)
        viewModel.sendRequest(signedLink(), "Robin", NOW)

        assertTrue(viewModel.remove(PEER_ID))

        assertTrue(viewModel.state.value.outgoingRequests.isEmpty())
        assertTrue(viewModel.state.value.friends.isEmpty())
        assertTrue(FriendStore(tempDir).outgoingRequests().isEmpty())
    }

    @Test
    fun `Connect presence marks a saved friend online across networks`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        val viewModel = FriendsViewModel(store)

        viewModel.updateRemotePresence(
            mapOf(
                PEER_ID to RemoteFriendPresence(
                    peerId = PEER_ID,
                    displayName = "Robin",
                    online = true,
                    description = "Robin's Remote World",
                    notifyWhenOnline = true,
                    route = ShareRoute.CONNECT,
                ),
            ),
        )

        val online = viewModel.state.value.friends.single()
        assertTrue(online.onlineViaConnect)
        assertEquals("Robin's Remote World", online.worldName)
    }

    @Test
    fun `playing on a server exposes request to join instead of direct join`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        val viewModel = FriendsViewModel(store)

        viewModel.updateActivities(
            mapOf(
                PEER_ID to FriendActivity(
                    FriendActivityKind.PLAYING_SERVER,
                    "Hypixel",
                ),
            ),
        )

        val friend = viewModel.state.value.friends.single()
        assertEquals(FriendActivityKind.PLAYING_SERVER, friend.activityKind)
        assertEquals("Hypixel", friend.activityDescription)
        assertTrue(friend.canRequestJoin)
        assertFalse(friend.canJoinNow)
    }

    @Test
    fun `joining a saved friend does not expose its stored capability`() = runTest {
        val link = signedLink()
        val invitation = ShareInviteCodec.decode(link, NOW).getOrNull()!!
        val node = FakeGuestNode()
        val browser = FabricShareBrowser.testing(
            node = node,
            now = { NOW },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        browser.start()
        node.discover(
            DirectP2pDiscoveredShare(
                "Robin's World",
                PEER_ID,
                LAN_ADDRESS,
                link,
            ),
        )
        val store = FriendStore(tempDir)
        store.accept(link, "Robin", NOW)
        val viewModel = FriendsViewModel(store)
        viewModel.updatePresence(
            listOf(
                DiscoveredLanShare(
                    "Robin's World",
                    link,
                    invitation,
                    LAN_ADDRESS,
                ),
            ),
        )

        val result = viewModel.join(
            peerId = PEER_ID,
            browser = browser,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        assertIs<arrow.core.Either.Right<GuestJoinTarget.Direct>>(result)
        assertEquals(listOf(LAN_ADDRESS), node.openedAddresses)
        browser.close()
    }

    @Test
    fun `retrying an outgoing request can join its signed route`() = runTest {
        val link = signedLink()
        val node = FakeGuestNode()
        val browser = FabricShareBrowser.testing(
            node = node,
            now = { NOW },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        browser.start()
        node.discover(
            DirectP2pDiscoveredShare(
                "Robin's World",
                PEER_ID,
                LAN_ADDRESS,
                link,
            ),
        )
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.sendRequest(link, "Robin", NOW)

        val result = viewModel.routeOutgoing(
            peerId = PEER_ID,
            browser = browser,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        assertIs<arrow.core.Either.Right<GuestJoinTarget.Direct>>(result)
        assertEquals(listOf(LAN_ADDRESS), node.openedAddresses)
        assertTrue(viewModel.state.value.friends.isEmpty())
        browser.close()
    }

    @Test
    fun `outgoing friend requests never use a Connect Minecraft endpoint`() = runTest {
        val link = signedLink()
        val browser = FabricShareBrowser.testing(
            node = FakeGuestNode(),
            now = { NOW },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.sendRequest(link, "Robin", NOW)

        val result = viewModel.routeOutgoing(
            peerId = PEER_ID,
            browser = browser,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        assertIs<arrow.core.Either.Left<GuestJoinFailure.NoRoute>>(result)
        browser.close()
    }

    private fun signedLink(
        displayName: String? = null,
    ): String {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = ShareInvitePayload(
            wireVersion = ShareInviteCodec.WIRE_VERSION,
            shareId = UUID.fromString(
                "9e511188-31a9-43ac-9107-29d94410d554",
            ),
            expiresAtEpochMillis = NOW.plusSeconds(3_600).toEpochMilli(),
            connectAddress = "purple-del.play.minekube.net",
            peerId = PEER_ID,
            internetDirectEnabled = false,
            directCandidates = emptyList(),
            capability = CAPABILITY,
            displayName = displayName,
        )
        val unsigned = ShareInviteCodec.unsignedBytes(
            payload,
            pair.public.encoded,
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(unsigned)
            sign()
        }
        return ShareInviteCodec.encode(
            SignedShareInvite(payload, pair.public.encoded, signature),
        )
    }

    private class FakeGuestNode : FabricGuestDirectNode {
        private var listener: DirectP2pDiscoveryListener? = null
        val openedAddresses = mutableListOf<String>()

        override fun peerId(): String = "12D3KooWGuest"

        override fun startDiscovery(listener: DirectP2pDiscoveryListener) {
            this.listener = listener
        }

        fun discover(share: DirectP2pDiscoveredShare) {
            listener?.onDiscovered(share)
        }

        override fun openProxy(
            address: String,
            shareId: String,
            capability: String,
            authMode: DirectP2pAuthMode,
            timeout: java.time.Duration,
        ): DirectP2pProxy {
            openedAddresses += address
            return DirectP2pProxy(
                InetSocketAddress(InetAddress.getLoopbackAddress(), 41_234),
            ) {}
        }

        override fun close() = Unit
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        const val PEER_ID = "12D3KooWStableFriendPeer"
        const val CAPABILITY = "friend-capability-123456789"
        const val LAN_ADDRESS =
            "/ip4/192.168.1.25/tcp/4001/p2p/$PEER_ID"
    }
}
