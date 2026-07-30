package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.tunnel.p2p.DirectP2pHostConfig
import com.minekube.connect.tunnel.p2p.DirectP2pHostHandler
import com.minekube.connect.tunnel.p2p.DirectP2pHostInfo
import com.minekube.connect.tunnel.p2p.Libp2pRuntime
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FabricDirectShareIngressTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `publishes a signed invitation with Connect fallback and opted-in candidates`() =
        runTest {
            val node = FakeDirectNode()
            val ingress = FabricDirectShareIngress.testing(
                nodeFactory = { node },
                now = { Instant.ofEpochMilli(NOW) },
                shareId = { SHARE_ID },
                capability = { CAPABILITY },
                displayName = { "Robin's World" },
                localSocket = { _, _ -> error("not opened during setup") },
            )

            val handle = ingress.start(
                options = OPTIONS.copy(allowInternetDirect = true),
                target = InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(),
                    25_565,
                ),
                connectAddress = "amber-fox.play.minekube.net",
            )
            val decoded = ShareInviteCodec.decode(
                handle.invitation,
                Instant.ofEpochMilli(NOW),
            )
            val invite = assertIs<Either.Right<SignedShareInvite>>(decoded).value

            assertEquals(SHARE_ID, invite.payload.shareId)
            assertEquals("amber-fox.play.minekube.net", invite.payload.connectAddress)
            assertEquals(node.hostInfo.peerId(), invite.payload.peerId)
            assertEquals(node.hostInfo.internetAddresses(), invite.payload.directCandidates)
            assertEquals(CAPABILITY, invite.payload.capability)
            assertTrue(invite.payload.internetDirectEnabled)
            assertTrue(handle.lanAvailable)
            assertTrue(handle.internetAvailable)
            assertEquals(handle.invitation, node.published)
            assertFalse(handle.toString().contains(CAPABILITY))

            handle.close()
            assertTrue(node.closed)
        }

    @Test
    fun `internet candidates are absent until the host opts in`() = runTest {
        val node = FakeDirectNode()
        val ingress = FabricDirectShareIngress.testing(
            nodeFactory = { node },
            now = { Instant.ofEpochMilli(NOW) },
            shareId = { SHARE_ID },
            capability = { CAPABILITY },
            displayName = { "World" },
            localSocket = { _, _ -> error("not opened during setup") },
        )

        val handle = ingress.start(
            options = OPTIONS,
            target = InetSocketAddress(
                java.net.InetAddress.getLoopbackAddress(),
                25_565,
            ),
            connectAddress = null,
        )
        val invite = assertIs<Either.Right<SignedShareInvite>>(
            ShareInviteCodec.decode(
                handle.invitation,
                Instant.ofEpochMilli(NOW),
            ),
        ).value

        assertFalse(invite.payload.internetDirectEnabled)
        assertTrue(invite.payload.directCandidates.isEmpty())
        assertEquals(null, invite.payload.connectAddress)
        assertFalse(handle.internetAvailable)
        handle.close()
    }

    @Test
    fun `partial startup closes the isolated node`() = runTest {
        val node = FakeDirectNode(failPublish = true)
        val ingress = FabricDirectShareIngress.testing(
            nodeFactory = { node },
            now = { Instant.ofEpochMilli(NOW) },
            shareId = { SHARE_ID },
            capability = { CAPABILITY },
            displayName = { "World" },
            localSocket = { _, _ -> error("not opened during setup") },
        )

        kotlin.test.assertFailsWith<IllegalStateException> {
            ingress.start(
                OPTIONS,
                InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(),
                    25_565,
                ),
                null,
            )
        }

        assertTrue(node.closed)
    }

    @Test
    fun `production ingress keeps its peer identity across share restarts`() = runTest {
        val target = InetSocketAddress(
            java.net.InetAddress.getLoopbackAddress(),
            25_565,
        )
        val firstIngress = FabricDirectShareIngress(
            dataDirectory = tempDir,
            displayName = { "First World" },
        )
        val first = firstIngress.start(OPTIONS, target, null)
        val firstPeerId = assertIs<Either.Right<SignedShareInvite>>(
            ShareInviteCodec.decode(first.invitation),
        ).value.payload.peerId
        first.close()
        Libp2pRuntime.close()

        val secondIngress = FabricDirectShareIngress(
            dataDirectory = tempDir,
            displayName = { "Second World" },
        )
        val second = secondIngress.start(OPTIONS, target, null)
        val secondPeerId = assertIs<Either.Right<SignedShareInvite>>(
            ShareInviteCodec.decode(second.invitation),
        ).value.payload.peerId

        assertEquals(firstPeerId, secondPeerId)
        second.close()
        Libp2pRuntime.close()
    }

    private class FakeDirectNode(
        private val failPublish: Boolean = false,
    ) : FabricDirectNode {
        private val keyPair: KeyPair =
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val hostInfo = DirectP2pHostInfo(
            "12D3KooWHost",
            keyPair.public.encoded,
            listOf(
                "/ip4/192.168.1.20/tcp/4001/p2p/12D3KooWHost",
            ),
            listOf(
                "/ip6/2001:db8::20/tcp/4001/p2p/12D3KooWHost",
            ),
        )
        var published: String? = null
        var closed = false

        override fun startHost(
            config: DirectP2pHostConfig,
            handler: DirectP2pHostHandler,
        ): DirectP2pHostInfo = hostInfo

        override fun sign(payload: ByteArray): ByteArray =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }

        override fun publish(invitation: String) {
            if (failPublish) {
                error("publish failed")
            }
            published = invitation
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val NOW = 1_785_384_000_000L
        val SHARE_ID: java.util.UUID =
            java.util.UUID.fromString("9e511188-31a9-43ac-9107-29d94410d554")
        const val CAPABILITY = "capability-123456789"
        val OPTIONS = ShareOptions(
            gameMode = ShareGameMode.SURVIVAL,
            allowCheats = false,
        )
    }
}
