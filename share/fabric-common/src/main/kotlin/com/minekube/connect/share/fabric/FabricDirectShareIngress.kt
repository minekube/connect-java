package com.minekube.connect.share.fabric

import com.minekube.connect.share.DirectShareHandle
import com.minekube.connect.share.DirectShareIngress
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.direct.DirectSessionRegistry
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.share.friend.ShareAccessIdentity
import com.minekube.connect.share.friend.ShareAccessIdentityStore
import com.minekube.connect.tunnel.p2p.DirectP2pHostConfig
import com.minekube.connect.tunnel.p2p.DirectP2pHostHandler
import com.minekube.connect.tunnel.p2p.DirectP2pHostInfo
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import com.minekube.connect.tunnel.p2p.DirectP2pSession
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class FabricDirectShareIngress private constructor(
    private val nodeFactory: () -> FabricDirectNode,
    private val now: () -> Instant,
    private val accessIdentity: () -> ShareAccessIdentity,
    private val displayName: () -> String,
    private val localSocket: (SocketAddress, DirectP2pSession) -> Socket,
) : DirectShareIngress {
    constructor(
        dataDirectory: Path,
        displayName: () -> String,
    ) : this(
        nodeFactory = {
            CoreFabricDirectNode(
                DirectP2pNode(dataDirectory.resolve(IDENTITY_FILE_NAME)),
            )
        },
        now = Instant::now,
        accessIdentity = ShareAccessIdentityStore(
            dataDirectory,
        )::currentOrCreate,
        displayName = displayName,
        localSocket = ::openTaggedLoopbackSocket,
    )

    internal constructor(
        node: FabricDirectNode,
        dataDirectory: Path,
        displayName: () -> String,
    ) : this(
        nodeFactory = { node },
        now = Instant::now,
        accessIdentity = ShareAccessIdentityStore(
            dataDirectory,
        )::currentOrCreate,
        displayName = displayName,
        localSocket = ::openTaggedLoopbackSocket,
    )

    override suspend fun start(
        options: ShareOptions,
        target: SocketAddress,
        connectAddress: String?,
    ): DirectShareHandle {
        val node = nodeFactory()
        try {
            val access = accessIdentity()
            val id = access.shareId
            val secret = access.capability
            val host = node.startHost(
                DirectP2pHostConfig(
                    id.toString(),
                    secret,
                    displayName().ifBlank { DEFAULT_DISPLAY_NAME },
                    options.allowInternetDirect,
                ),
                DirectP2pHostHandler { session ->
                    localSocket(target, session)
                },
            )
            val internetCandidates = if (options.allowInternetDirect) {
                host.internetAddresses()
            } else {
                emptyList()
            }
            val payload = ShareInvitePayload(
                wireVersion = ShareInviteCodec.WIRE_VERSION,
                shareId = id,
                expiresAtEpochMillis = now()
                    .plusSeconds(INVITATION_LIFETIME_SECONDS)
                    .toEpochMilli(),
                connectAddress = connectAddress,
                peerId = host.peerId(),
                internetDirectEnabled = options.allowInternetDirect,
                directCandidates = internetCandidates,
                capability = secret,
            )
            val unsigned = ShareInviteCodec.unsignedBytes(
                payload,
                host.publicKey(),
            )
            val invitation = ShareInviteCodec.encode(
                SignedShareInvite(
                    payload = payload,
                    publicKey = host.publicKey(),
                    signature = node.sign(unsigned),
                ),
            )
            node.publish(invitation)
            val closed = AtomicBoolean()
            return DirectShareHandle(
                invitation = invitation,
                lanAvailable = true,
                internetAvailable =
                    options.allowInternetDirect &&
                        internetCandidates.isNotEmpty(),
                close = {
                    if (closed.compareAndSet(false, true)) {
                        node.close()
                    }
                },
            )
        } catch (failure: Throwable) {
            try {
                node.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        }
    }

    companion object {
        internal fun testing(
            nodeFactory: () -> FabricDirectNode,
            now: () -> Instant,
            shareId: () -> UUID,
            capability: () -> String,
            displayName: () -> String,
            localSocket: (SocketAddress, DirectP2pSession) -> Socket,
        ) = FabricDirectShareIngress(
            nodeFactory = nodeFactory,
            now = now,
            accessIdentity = {
                ShareAccessIdentity(
                    shareId = shareId(),
                    capability = capability(),
                )
            },
            displayName = displayName,
            localSocket = localSocket,
        )

        private fun openTaggedLoopbackSocket(
            target: SocketAddress,
            session: DirectP2pSession,
        ): Socket {
            val destination = target as? InetSocketAddress
                ?: throw IllegalArgumentException(
                    "Direct Minecraft target must be an internet socket",
                )
            check(destination.address.isLoopbackAddress) {
                "Direct Minecraft target escaped loopback"
            }
            val socket = Socket()
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val registration = DirectSessionRegistry.register(
                sourcePort = socket.localPort,
                session = session,
            )
            try {
                socket.connect(destination, LOCAL_CONNECT_TIMEOUT_MILLIS)
                return socket
            } catch (failure: Throwable) {
                registration.close()
                try {
                    socket.close()
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== failure) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
        }

        private const val DEFAULT_DISPLAY_NAME = "Minecraft world"
        private const val IDENTITY_FILE_NAME = "share-libp2p-identity.key"
        private const val INVITATION_LIFETIME_SECONDS = 24 * 60 * 60L
        private const val LOCAL_CONNECT_TIMEOUT_MILLIS = 3_000
    }
}

internal interface FabricDirectNode : AutoCloseable {
    fun startHost(
        config: DirectP2pHostConfig,
        handler: DirectP2pHostHandler,
    ): DirectP2pHostInfo

    fun sign(payload: ByteArray): ByteArray

    fun publish(invitation: String)
}

private class CoreFabricDirectNode(
    private val node: DirectP2pNode,
) : FabricDirectNode {
    override fun startHost(
        config: DirectP2pHostConfig,
        handler: DirectP2pHostHandler,
    ): DirectP2pHostInfo = node.startHost(config, handler)

    override fun sign(payload: ByteArray): ByteArray = node.sign(payload)

    override fun publish(invitation: String) {
        node.publish(invitation)
    }

    override fun close() {
        node.close()
    }
}
