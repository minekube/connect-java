package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveredShare
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class FabricShareBrowserTest {
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
    fun `failed direct reachability falls back to Connect exactly once`() =
        runTest {
            val node = FakeGuestNode(failDirect = true)
            val browser = browser(node)

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

    private fun kotlinx.coroutines.test.TestScope.browser(node: FakeGuestNode) =
        FabricShareBrowser.testing(
            node = node,
            now = { Instant.ofEpochMilli(NOW) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun invitation(): String {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = ShareInvitePayload(
            wireVersion = ShareInviteCodec.WIRE_VERSION,
            shareId = SHARE_ID,
            expiresAtEpochMillis = NOW + 60_000,
            connectAddress = "amber-fox.play.minekube.net",
            peerId = PEER_ID,
            internetDirectEnabled = true,
            directCandidates = listOf(INTERNET_ADDRESS),
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

    private class FakeGuestNode(
        private val failDirect: Boolean = false,
    ) : FabricGuestDirectNode {
        private var listener: DirectP2pDiscoveryListener? = null
        val openedAddresses = mutableListOf<String>()

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
