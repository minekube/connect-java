package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.share.friend.SavedFriend
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveredShare
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FabricShareBrowserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `guest peer identity survives browser restarts`() {
        val first = FabricShareBrowser(tempDir)
        val firstPeerId = first.peerId
        first.close()

        val second = FabricShareBrowser(tempDir)

        assertEquals(firstPeerId, second.peerId)
        assertTrue(firstPeerId.isNotBlank())
        second.close()
    }

    @Test
    fun `valid mDNS metadata becomes a LAN share without exposing secrets`() =
        runTest {
            val node = FakeGuestNode()
            val browser = browser(node)
            browser.start()
            val invitation = invitation()

            node.discover(
                DirectP2pDiscoveredShare(
                    "Robin's World",
                    PEER_ID,
                    LAN_ADDRESS,
                    invitation,
                ),
            )

            val discovered = browser.discovered.value.single()
            assertEquals("Robin's World", discovered.displayName)
            assertEquals(SHARE_ID, discovered.invitation.payload.shareId)
            assertTrue(discovered.toString().contains("<redacted>"))
            browser.close()
        }

    @Test
    fun `LAN is selected before internet and Connect`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)

        val result = browser.join(
            invitationUri = invitation(),
            lanAddress = LAN_ADDRESS,
            internetOptIn = true,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        val target = assertIs<Either.Right<GuestJoinTarget.Direct>>(result).value
        assertEquals(ShareRoute.DIRECT_LAN, target.route)
        assertEquals(listOf(LAN_ADDRESS), node.openedAddresses)
        target.close()
        browser.close()
    }

    @Test
    fun `pasted invitation uses its matching discovered LAN address`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)
        browser.start()
        val invitation = invitation()
        node.discover(
            DirectP2pDiscoveredShare(
                "Robin's World",
                PEER_ID,
                LAN_ADDRESS,
                invitation,
            ),
        )

        val result = browser.join(
            invitationUri = invitation,
            lanAddress = null,
            internetOptIn = false,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        val target = assertIs<Either.Right<GuestJoinTarget.Direct>>(result).value
        assertEquals(ShareRoute.DIRECT_LAN, target.route)
        assertEquals(listOf(LAN_ADDRESS), node.openedAddresses)
        target.close()
        browser.close()
    }

    @Test
    fun `saved friend resolves a fresh LAN address without another link`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)
        browser.start()
        val invitation = invitation()
        val friend = savedFriend(invitation)
        node.discover(
            DirectP2pDiscoveredShare(
                "Robin's New World",
                PEER_ID,
                LAN_ADDRESS,
                invitation,
            ),
        )

        val result = browser.join(
            friend = friend,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        val target = assertIs<Either.Right<GuestJoinTarget.Direct>>(result).value
        assertEquals(ShareRoute.DIRECT_LAN, target.route)
        assertEquals(listOf(LAN_ADDRESS), node.openedAddresses)
        target.close()
        browser.close()
    }

    @Test
    fun `saved friend route survives another peer advertising the same share`() =
        runTest {
            val node = FakeGuestNode()
            val browser = browser(node)
            browser.start()
            val friendLink = invitation()
            val friend = savedFriend(friendLink)
            node.discover(
                DirectP2pDiscoveredShare(
                    "Robin's friend control",
                    PEER_ID,
                    LAN_ADDRESS,
                    friendLink,
                ),
            )
            val worldPeer = "12D3KooWWorld"
            node.discover(
                DirectP2pDiscoveredShare(
                    "Robin's active world",
                    worldPeer,
                    lanAddress(worldPeer),
                    invitation(peerId = worldPeer),
                ),
            )

            val result = browser.openFriendControl(
                friend = friend,
                authMode = DirectP2pAuthMode.OFFLINE,
            )

            val target = assertIs<Either.Right<GuestJoinTarget.Direct>>(
                result,
            ).value
            assertEquals(ShareRoute.DIRECT_LAN, target.route)
            assertEquals(listOf(LAN_ADDRESS), node.openedAddresses)
            assertEquals(2, browser.discovered.value.size)
            target.close()
            browser.close()
        }

    @Test
    fun `friend control uses saved direct internet route outside the LAN`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)
        val friend = savedFriend(invitation())

        val result = browser.openFriendControl(
            friend = friend,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        val target = assertIs<Either.Right<GuestJoinTarget.Direct>>(result).value
        assertEquals(ShareRoute.DIRECT_INTERNET, target.route)
        assertEquals(listOf(INTERNET_ADDRESS), node.openedAddresses)
        target.close()
        browser.close()
    }

    @Test
    fun `saved friend never probes persisted internet without guest consent`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)
        val friend = savedFriend(invitation()).copy(
            internetDirectGuestOptIn = false,
        )

        val result = browser.openFriendControl(
            friend = friend,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        assertEquals(GuestJoinFailure.NoRoute, result.leftOrNull())
        assertTrue(node.openedAddresses.isEmpty())
        browser.close()
    }

    @Test
    fun `saved friend ignores LAN metadata signed by a different identity`() = runTest {
        val node = FakeGuestNode()
        val reports = mutableListOf<String>()
        val browser = browser(node, reports::add)
        browser.start()
        val friend = savedFriend(invitation())
        node.discover(
            DirectP2pDiscoveredShare(
                "Impostor World",
                PEER_ID,
                LAN_ADDRESS,
                invitation(),
            ),
        )

        val result = browser.join(
            friend = friend,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        val target = assertIs<Either.Right<GuestJoinTarget.Direct>>(result).value
        assertEquals(ShareRoute.DIRECT_INTERNET, target.route)
        assertEquals(listOf(INTERNET_ADDRESS), node.openedAddresses)
        assertEquals(
            listOf(
                "Connect Share route: direct LAN unavailable",
                "Connect Share route: direct internet",
            ),
            reports,
        )
        target.close()
        browser.close()
    }

    @Test
    fun `saved friend never falls back through this profiles own Connect endpoint`() =
        runTest {
            val node = FakeGuestNode(failDirect = true)
            val browser = browser(node)
            val friend = savedFriend(invitation())

            val result = browser.join(
                friend = friend,
                authMode = DirectP2pAuthMode.OFFLINE,
                ownConnectAddress = friend.connectAddress,
            )

            assertEquals(
                GuestJoinFailure.EndpointConflict,
                result.leftOrNull(),
            )
            assertEquals(listOf(INTERNET_ADDRESS), node.openedAddresses)
            browser.close()
        }

    @Test
    fun `saved friend falls back to Connect after persisted direct route fails`() =
        runTest {
            val node = FakeGuestNode(failDirect = true)
            val reports = mutableListOf<String>()
            val browser = browser(node, reports::add)
            val friend = savedFriend(invitation())

            val result = browser.join(
                friend = friend,
                authMode = DirectP2pAuthMode.OFFLINE,
            )

            assertIs<Either.Right<GuestJoinTarget.Connect>>(result)
            assertEquals(listOf(INTERNET_ADDRESS), node.openedAddresses)
            assertEquals(
                listOf(
                    "Connect Share route: direct LAN unavailable",
                    "Connect Share route: direct internet unavailable",
                    "Connect Share route: using Connect fallback",
                ),
                reports,
            )
            browser.close()
        }

    @Test
    fun `LAN discovery is world ready only after status succeeds through proxy`() =
        runTest {
            val node = FakeGuestNode()
            val browser = browser(node)
            val link = invitation()
            val friend = savedFriend(link)
            browser.start()
            node.discover(
                DirectP2pDiscoveredShare(
                    "Robin's LAN World",
                    PEER_ID,
                    lanAddress(PEER_ID),
                    link,
                ),
            )
            val probed = mutableListOf<String>()

            val presence = browser.probeLan(
                friend = friend,
                authMode = DirectP2pAuthMode.OFFLINE,
                probe = FriendStatusProbe { address ->
                    probed += address
                    Either.Right(ServerPresence("Robin's LAN World"))
                },
            )

            assertEquals(ServerPresence("Robin's LAN World"), presence)
            assertEquals(1, probed.size)
            assertTrue(probed.single().endsWith(":41234"))
            browser.close()
        }

    @Test
    fun `presence probes persisted internet routes after LAN`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)
        val friend = savedFriend(invitation())
        val probed = mutableListOf<String>()

        val presence = browser.probeDirect(
            friend = friend,
            authMode = DirectP2pAuthMode.OFFLINE,
            probe = FriendStatusProbe { address ->
                probed += address
                Either.Right(ServerPresence("Robin's World"))
            },
        )

        assertEquals(
            ServerPresence("Robin's World", ShareRoute.DIRECT_INTERNET),
            presence,
        )
        assertEquals(1, probed.size)
        assertEquals(listOf(INTERNET_ADDRESS), node.openedAddresses)
        browser.close()
    }

    @Test
    fun `pasted invitation ignores discovery with a different peer`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)
        browser.start()
        val otherPeer = "12D3KooWOther"
        node.discover(
            DirectP2pDiscoveredShare(
                "Other World",
                otherPeer,
                lanAddress(otherPeer),
                invitation(peerId = otherPeer),
            ),
        )

        val result = browser.join(
            invitationUri = invitation(),
            lanAddress = null,
            internetOptIn = false,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        assertIs<Either.Right<GuestJoinTarget.Connect>>(result)
        assertTrue(node.openedAddresses.isEmpty())
        browser.close()
    }

    @Test
    fun `pasted invitation ignores discovery with a different share`() = runTest {
        val node = FakeGuestNode()
        val browser = browser(node)
        browser.start()
        val otherShare = UUID.fromString(
            "72a5d404-0ef9-48bc-882b-a2ec896afbe5",
        )
        node.discover(
            DirectP2pDiscoveredShare(
                "Other World",
                PEER_ID,
                LAN_ADDRESS,
                invitation(shareId = otherShare),
            ),
        )

        val result = browser.join(
            invitationUri = invitation(),
            lanAddress = null,
            internetOptIn = false,
            authMode = DirectP2pAuthMode.OFFLINE,
        )

        assertIs<Either.Right<GuestJoinTarget.Connect>>(result)
        assertTrue(node.openedAddresses.isEmpty())
        browser.close()
    }

    @Test
    fun `failed direct reachability falls back to Connect exactly once`() =
        runTest {
            val node = FakeGuestNode(failDirect = true)
            val reports = mutableListOf<String>()
            val browser = browser(node, reports::add)

            val result = browser.join(
                invitationUri = invitation(),
                lanAddress = LAN_ADDRESS,
                internetOptIn = true,
                authMode = DirectP2pAuthMode.ONLINE,
            )

            val target = assertIs<Either.Right<GuestJoinTarget.Connect>>(result).value
            assertEquals("amber-fox.play.minekube.net", target.publicAddress)
            assertEquals(
                listOf(LAN_ADDRESS, INTERNET_ADDRESS),
                node.openedAddresses,
            )
            assertEquals(
                listOf(
                    "Connect Share route: direct LAN unavailable",
                    "Connect Share route: direct internet unavailable",
                    "Connect Share route: using Connect fallback",
                ),
                reports,
            )
            browser.close()
        }

    @Test
    fun `guest internet opt in is required even when host enabled it`() =
        runTest {
            val node = FakeGuestNode(failDirect = true)
            val browser = browser(node)

            val result = browser.join(
                invitationUri = invitation(),
                lanAddress = null,
                internetOptIn = false,
                authMode = DirectP2pAuthMode.ONLINE,
            )

            assertIs<Either.Right<GuestJoinTarget.Connect>>(result)
            assertTrue(node.openedAddresses.isEmpty())
            browser.close()
        }

    private fun kotlinx.coroutines.test.TestScope.browser(
        node: FakeGuestNode,
        routeReporter: (String) -> Unit = {},
    ) =
        FabricShareBrowser.testing(
            node = node,
            now = { Instant.ofEpochMilli(NOW) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            routeReporter = routeReporter,
        )

    private fun invitation(
        shareId: UUID = SHARE_ID,
        peerId: String = PEER_ID,
    ): String {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = ShareInvitePayload(
            wireVersion = ShareInviteCodec.WIRE_VERSION,
            shareId = shareId,
            expiresAtEpochMillis = NOW + 60_000,
            connectAddress = "amber-fox.play.minekube.net",
            peerId = peerId,
            internetDirectEnabled = true,
            directCandidates = listOf(internetAddress(peerId)),
            capability = CAPABILITY,
        )
        val unsigned = ShareInviteCodec.unsignedBytes(payload, pair.public.encoded)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(unsigned)
            sign()
        }
        return ShareInviteCodec.encode(
            SignedShareInvite(payload, pair.public.encoded, signature),
        )
    }

    private fun savedFriend(invitationUri: String): SavedFriend {
        val invitation = ShareInviteCodec.decode(
            invitationUri,
            Instant.ofEpochMilli(NOW),
        ).getOrNull()!!
        return SavedFriend(
            peerId = invitation.payload.peerId,
            publicKeyBase64 = Base64.getEncoder()
                .encodeToString(invitation.publicKey),
            shareId = invitation.payload.shareId,
            capability = invitation.payload.capability,
            connectAddress = invitation.payload.connectAddress,
            internetDirectEnabled = invitation.payload.internetDirectEnabled,
            directCandidates = invitation.payload.directCandidates,
            internetDirectGuestOptIn = true,
            displayName = "Robin",
        )
    }

    private fun lanAddress(peerId: String) =
        "/ip4/192.168.1.20/tcp/4001/p2p/$peerId"

    private fun internetAddress(peerId: String) =
        "/ip6/2001:db8::20/tcp/4001/p2p/$peerId"

    private class FakeGuestNode(
        private val failDirect: Boolean = false,
    ) : FabricGuestDirectNode {
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
            timeout: Duration,
        ): DirectP2pProxy {
            openedAddresses += address
            if (failDirect) {
                error("unreachable")
            }
            return DirectP2pProxy(
                InetSocketAddress(InetAddress.getLoopbackAddress(), 41_234),
            ) {}
        }

        override fun close() = Unit
    }

    private companion object {
        const val NOW = 1_785_384_000_000L
        val SHARE_ID: UUID =
            UUID.fromString("9e511188-31a9-43ac-9107-29d94410d554")
        const val PEER_ID = "12D3KooWHost"
        const val CAPABILITY = "capability-secret"
        const val LAN_ADDRESS =
            "/ip4/192.168.1.20/tcp/4001/p2p/12D3KooWHost"
        const val INTERNET_ADDRESS =
            "/ip6/2001:db8::20/tcp/4001/p2p/12D3KooWHost"
    }
}
