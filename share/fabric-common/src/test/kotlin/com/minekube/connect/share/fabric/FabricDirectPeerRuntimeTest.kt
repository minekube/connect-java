package com.minekube.connect.share.fabric

import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pHostConfig
import com.minekube.connect.tunnel.p2p.DirectP2pHostHandler
import com.minekube.connect.tunnel.p2p.DirectP2pHostInfo
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FabricDirectPeerRuntimeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `title host and browser share one libp2p node`() = runTest {
        val node = RecordingPeerNode()
        val runtime = FabricDirectPeerRuntime.testing(
            node = node,
            dataDirectory = tempDir,
            displayName = { "Title friend host" },
        )

        assertTrue(runtime.browser.start().isRight())
        val handle = runtime.ingress.start(
            options = ShareOptions(
                gameMode = ShareGameMode.SURVIVAL,
                allowCheats = false,
            ),
            target = InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                25_565,
            ),
            connectAddress = "title.play.minekube.net",
        )

        assertEquals(1, node.discoveryStarts)
        assertEquals(1, node.hostStarts)
        assertEquals(1, node.publishes)

        handle.close()
        runtime.browser.close()
    }

    @Test
    fun `world refresh keeps the shared peer alive until the runtime closes`() =
        runTest {
            val node = RecordingPeerNode()
            val runtime = FabricDirectPeerRuntime.testing(
                node = node,
                dataDirectory = tempDir,
                displayName = { "Current world" },
            )

            assertTrue(runtime.browser.start().isRight())
            val first = runtime.ingress.start(
                options = ShareOptions(
                    gameMode = ShareGameMode.SURVIVAL,
                    allowCheats = false,
                ),
                target = InetSocketAddress(
                    InetAddress.getLoopbackAddress(),
                    25_565,
                ),
                connectAddress = "stable.play.minekube.net",
            )
            first.close()
            val refreshed = runtime.ingress.start(
                options = ShareOptions(
                    gameMode = ShareGameMode.SURVIVAL,
                    allowCheats = false,
                    allowInternetDirect = true,
                ),
                target = InetSocketAddress(
                    InetAddress.getLoopbackAddress(),
                    25_565,
                ),
                connectAddress = "stable.play.minekube.net",
            )
            refreshed.close()

            assertEquals(2, node.hostStarts)
            assertEquals(2, node.publishes)
            assertEquals(0, node.closes)
            runtime.browser.close()
            assertEquals(1, node.closes)
        }

    private class RecordingPeerNode : FabricDirectPeerNode {
        private val keyPair =
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        var discoveryStarts = 0
        var hostStarts = 0
        var publishes = 0
        var closes = 0

        override fun peerId(): String = PEER_ID

        override fun startDiscovery(listener: DirectP2pDiscoveryListener) {
            discoveryStarts++
        }

        override fun startHost(
            config: DirectP2pHostConfig,
            handler: DirectP2pHostHandler,
        ): DirectP2pHostInfo {
            hostStarts++
            return DirectP2pHostInfo(
                PEER_ID,
                keyPair.public.encoded,
                listOf("/ip4/127.0.0.1/tcp/4001/p2p/$PEER_ID"),
                emptyList(),
            )
        }

        override fun sign(payload: ByteArray): ByteArray =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }

        override fun publish(
            invitation: String,
            discoveryInvitation: String,
        ) {
            publishes++
        }

        override fun openProxy(
            address: String,
            shareId: String,
            capability: String,
            authMode: DirectP2pAuthMode,
            timeout: Duration,
        ): DirectP2pProxy = error("not used")

        override fun close() {
            closes++
        }
    }

    private companion object {
        const val PEER_ID =
            "12D3KooWEHeJnnq1Rfwt679bTyTxkEdtyTC8peAJWsWCxtAJ4s9y"
    }
}
