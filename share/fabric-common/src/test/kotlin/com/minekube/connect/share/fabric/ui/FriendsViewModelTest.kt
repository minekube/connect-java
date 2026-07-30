package com.minekube.connect.share.fabric.ui

import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.share.fabric.DiscoveredLanShare
import com.minekube.connect.share.fabric.FabricGuestDirectNode
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.fabric.RemoteFriendPresence
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveredShare
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendPermissions
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FriendsViewModelTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `accepting one link exposes a safe saved friend summary`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))

        assertTrue(viewModel.accept(signedLink(), "Robin", NOW))

        val friend = viewModel.state.value.friends.single()
        assertEquals(PEER_ID, friend.peerId)
        assertEquals("Robin", friend.displayName)
        assertTrue(friend.connectAvailable)
        assertTrue(friend.permissions.notifyWhenOnline)
        assertFalse(viewModel.state.value.toString().contains(CAPABILITY))
        assertEquals(null, viewModel.state.value.safeMessage)
    }

    @Test
    fun `invalid friend link stays on the add flow with a useful message`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))

        val accepted = viewModel.accept(
            "minekube://share/not-a-valid-link",
            "Robin",
            NOW,
        )

        assertFalse(accepted)
        assertTrue(viewModel.state.value.friends.isEmpty())
        assertTrue(viewModel.state.value.safeMessage?.isNotBlank() == true)
    }

    @Test
    fun `saved friend can be renamed configured and removed`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.accept(signedLink(), "Robin", NOW)

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

        viewModel.remove(PEER_ID)

        assertTrue(viewModel.state.value.friends.isEmpty())
    }

    @Test
    fun `matching discovery marks a saved friend world ready to join`() {
        val link = signedLink()
        val invitation = ShareInviteCodec.decode(link, NOW).getOrNull()!!
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.accept(link, "Robin", NOW)

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

        val online = viewModel.state.value.friends.single()
        assertTrue(online.onlineViaLan)
        assertEquals("Robin's New World", online.worldName)

        viewModel.updatePresence(emptyList())

        assertFalse(viewModel.state.value.friends.single().onlineViaLan)
    }

    @Test
    fun `Connect presence marks a saved friend online across networks`() {
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.accept(signedLink(), "Robin", NOW)

        viewModel.updateRemotePresence(
            mapOf(
                PEER_ID to RemoteFriendPresence(
                    peerId = PEER_ID,
                    displayName = "Robin",
                    online = true,
                    description = "Robin's Remote World",
                    notifyWhenOnline = true,
                ),
            ),
        )

        val online = viewModel.state.value.friends.single()
        assertTrue(online.onlineViaConnect)
        assertEquals("Robin's Remote World", online.worldName)
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
        val viewModel = FriendsViewModel(FriendStore(tempDir))
        viewModel.accept(link, "Robin", NOW)
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

    private fun signedLink(): String {
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
