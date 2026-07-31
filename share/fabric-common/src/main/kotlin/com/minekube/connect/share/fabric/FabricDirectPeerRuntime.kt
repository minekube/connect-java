package com.minekube.connect.share.fabric

import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pHostConfig
import com.minekube.connect.tunnel.p2p.DirectP2pHostHandler
import com.minekube.connect.tunnel.p2p.DirectP2pHostInfo
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

internal class FabricDirectPeerRuntime private constructor(
    val browser: FabricShareBrowser,
    val ingress: FabricDirectShareIngress,
) {
    constructor(
        dataDirectory: Path,
        displayName: () -> String,
    ) : this(
        node = CoreFabricDirectPeerNode(
            DirectP2pNode(dataDirectory.resolve(IDENTITY_FILE_NAME)),
        ),
        dataDirectory = dataDirectory,
        displayName = displayName,
    )

    private constructor(
        node: FabricDirectPeerNode,
        dataDirectory: Path,
        displayName: () -> String,
    ) : this(
        browser = FabricShareBrowser(node),
        ingress = FabricDirectShareIngress(
            node = node,
            dataDirectory = dataDirectory,
            displayName = displayName,
        ),
    )

    companion object {
        internal fun testing(
            node: FabricDirectPeerNode,
            dataDirectory: Path,
            displayName: () -> String,
        ) = FabricDirectPeerRuntime(
            node = node,
            dataDirectory = dataDirectory,
            displayName = displayName,
        )

        private const val IDENTITY_FILE_NAME =
            "share-libp2p-identity.key"
    }
}

internal interface FabricDirectPeerNode :
    FabricGuestDirectNode,
    FabricDirectNode

private class CoreFabricDirectPeerNode(
    private val node: DirectP2pNode,
) : FabricDirectPeerNode {
    private val closed = AtomicBoolean()

    override fun peerId(): String = node.peerId()

    override fun startDiscovery(listener: DirectP2pDiscoveryListener) {
        node.startDiscovery(listener)
    }

    override fun startHost(
        config: DirectP2pHostConfig,
        handler: DirectP2pHostHandler,
    ): DirectP2pHostInfo = node.startHost(config, handler)

    override fun sign(payload: ByteArray): ByteArray = node.sign(payload)

    override fun publish(invitation: String) {
        node.publish(invitation)
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
        if (closed.compareAndSet(false, true)) {
            node.close()
        }
    }
}
