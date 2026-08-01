package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInviteError
import com.minekube.connect.share.direct.ShareJoinError
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.share.direct.TransportSelector
import com.minekube.connect.share.friend.SavedFriend
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveredShare
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class DiscoveredLanShare(
    val displayName: String,
    val invitationUri: String,
    val invitation: SignedShareInvite,
    val lanAddress: String,
) {
    override fun toString(): String =
        "DiscoveredLanShare(displayName=$displayName, " +
            "invitationUri=<redacted>, invitation=<redacted>, " +
            "lanAddress=<redacted>)"
}

sealed interface GuestJoinTarget : AutoCloseable {
    val route: ShareRoute

    data class Connect(
        val publicAddress: String,
    ) : GuestJoinTarget {
        override val route: ShareRoute = ShareRoute.CONNECT
        override fun close() = Unit
    }

    class Direct(
        override val route: ShareRoute,
        val localAddress: InetSocketAddress,
        private val proxy: DirectP2pProxy,
    ) : GuestJoinTarget {
        override fun close() {
            proxy.close()
        }

        override fun toString(): String =
            "Direct(route=$route, localAddress=$localAddress)"
    }
}

sealed interface GuestJoinFailure {
    val safeMessage: String

    data class InvalidInvitation(
        val error: ShareInviteError,
    ) : GuestJoinFailure {
        override val safeMessage: String = error.safeMessage
    }

    data object PeerMismatch : GuestJoinFailure {
        override val safeMessage =
            "The discovered host does not match this Connect Share invitation"
    }

    data object DiscoveryUnavailable : GuestJoinFailure {
        override val safeMessage =
            "Automatic LAN discovery is unavailable; paste a Connect Share invitation"
    }

    data object NoRoute : GuestJoinFailure {
        override val safeMessage: String = ShareJoinError.NoRoute.safeMessage
    }

    data object EndpointConflict : GuestJoinFailure {
        override val safeMessage =
            "This profile uses the same Connect endpoint as your friend; reset one profile's Connect identity"
    }
}

class FabricShareBrowser private constructor(
    private val node: FabricGuestDirectNode,
    private val now: () -> Instant,
    private val ioDispatcher: CoroutineDispatcher,
    private val routeReporter: (String) -> Unit,
) : AutoCloseable {
    constructor() : this(
        node = CoreFabricGuestDirectNode(DirectP2pNode()),
        now = Instant::now,
        ioDispatcher = Dispatchers.IO,
        routeReporter = LOGGER::info,
    )

    constructor(dataDirectory: Path) : this(
        node = CoreFabricGuestDirectNode(
            DirectP2pNode(dataDirectory.resolve(IDENTITY_FILE_NAME)),
        ),
        now = Instant::now,
        ioDispatcher = Dispatchers.IO,
        routeReporter = LOGGER::info,
    )

    internal constructor(node: FabricGuestDirectNode) : this(
        node = node,
        now = Instant::now,
        ioDispatcher = Dispatchers.IO,
        routeReporter = LOGGER::info,
    )

    private val mutableDiscovered =
        MutableStateFlow<List<DiscoveredLanShare>>(emptyList())
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    val discovered: StateFlow<List<DiscoveredLanShare>> =
        mutableDiscovered.asStateFlow()
    val peerId: String
        get() = node.peerId()

    fun start(): Either<GuestJoinFailure, Unit> {
        if (started.get()) {
            return Unit.right()
        }
        return Either.catch {
            node.startDiscovery(::onDiscovered)
            started.set(true)
        }.mapLeft {
            GuestJoinFailure.DiscoveryUnavailable
        }
    }

    fun parse(
        invitationUri: String,
    ): Either<GuestJoinFailure, SignedShareInvite> =
        ShareInviteCodec.decode(invitationUri.trim(), now())
            .mapLeft(GuestJoinFailure::InvalidInvitation)

    suspend fun join(
        invitationUri: String,
        lanAddress: String?,
        internetOptIn: Boolean,
        authMode: DirectP2pAuthMode,
    ): Either<GuestJoinFailure, GuestJoinTarget> {
        val invitation = parse(invitationUri).fold(
            ifLeft = { return it.left() },
            ifRight = { it },
        )
        val payload = invitation.payload
        val effectiveLanAddress =
            lanAddress ?: matchingLanAddress(invitation)
        val routes = TransportSelector.plan(
            sameLan = effectiveLanAddress != null,
            hostInternetOptIn = payload.internetDirectEnabled,
            guestInternetOptIn = internetOptIn,
            connectAddress = payload.connectAddress,
        )
        return withContext(ioDispatcher) {
            for (route in routes.distinct()) {
                when (route) {
                    ShareRoute.DIRECT_LAN -> {
                        val address = effectiveLanAddress ?: continue
                        val direct = openDirect(
                            route,
                            address,
                            invitation,
                            authMode,
                            LAN_TIMEOUT,
                        )
                        if (direct != null) {
                            reportRoute(ROUTE_DIRECT_LAN)
                            return@withContext direct.right()
                        }
                        reportRoute(ROUTE_DIRECT_LAN_UNAVAILABLE)
                    }

                    ShareRoute.DIRECT_INTERNET -> {
                        var attempted = false
                        for (address in payload.directCandidates) {
                            attempted = true
                            val direct = openDirect(
                                route,
                                address,
                                invitation,
                                authMode,
                                INTERNET_TIMEOUT,
                            )
                            if (direct != null) {
                                reportRoute(ROUTE_DIRECT_INTERNET)
                                return@withContext direct.right()
                            }
                        }
                        if (attempted) {
                            reportRoute(
                                ROUTE_DIRECT_INTERNET_UNAVAILABLE,
                            )
                        }
                    }

                    ShareRoute.CONNECT -> {
                        payload.connectAddress?.let {
                            reportRoute(ROUTE_CONNECT_FALLBACK)
                            return@withContext GuestJoinTarget.Connect(it).right()
                        }
                    }
                }
            }
            GuestJoinFailure.NoRoute.left()
        }
    }

    suspend fun join(
        friend: SavedFriend,
        authMode: DirectP2pAuthMode,
        ownConnectAddress: String? = null,
    ): Either<GuestJoinFailure, GuestJoinTarget> =
        withContext(ioDispatcher) {
            val discovered = matchingLanShare(friend)
            if (discovered != null) {
                val direct = openDirect(
                    route = ShareRoute.DIRECT_LAN,
                    address = discovered.lanAddress,
                    shareId = friend.shareId.toString(),
                    capability = friend.capability,
                    authMode = authMode,
                    timeout = LAN_TIMEOUT,
                )
                if (direct != null) {
                    reportRoute(ROUTE_DIRECT_LAN)
                    return@withContext direct.right()
                }
                reportRoute(ROUTE_DIRECT_LAN_UNAVAILABLE)
            } else {
                reportRoute(ROUTE_DIRECT_LAN_UNAVAILABLE)
            }
            if (friend.internetDirectEnabled && friend.internetDirectGuestOptIn) {
                var attempted = false
                for (address in friend.directCandidates) {
                    attempted = true
                    val direct = openDirect(
                        route = ShareRoute.DIRECT_INTERNET,
                        address = address,
                        shareId = friend.shareId.toString(),
                        capability = friend.capability,
                        authMode = authMode,
                        timeout = INTERNET_TIMEOUT,
                    )
                    if (direct != null) {
                        reportRoute(ROUTE_DIRECT_INTERNET)
                        return@withContext direct.right()
                    }
                }
                if (attempted) {
                    reportRoute(ROUTE_DIRECT_INTERNET_UNAVAILABLE)
                }
            }
            if (
                connectAddressesMatch(
                    friend.connectAddress,
                    ownConnectAddress,
                )
            ) {
                reportRoute(ROUTE_ENDPOINT_CONFLICT)
                return@withContext GuestJoinFailure.EndpointConflict.left()
            }
            friend.connectAddress?.let {
                reportRoute(ROUTE_CONNECT_FALLBACK)
                return@withContext GuestJoinTarget.Connect(it).right()
            }
            GuestJoinFailure.NoRoute.left()
        }

    suspend fun openFriendControl(
        friend: SavedFriend,
        authMode: DirectP2pAuthMode,
    ): Either<GuestJoinFailure, GuestJoinTarget.Direct> =
        withContext(ioDispatcher) {
            matchingLanShare(friend)?.let { discovered ->
                openDirect(
                    route = ShareRoute.DIRECT_LAN,
                    address = discovered.lanAddress,
                    shareId = friend.shareId.toString(),
                    capability = friend.capability,
                    authMode = authMode,
                    timeout = LAN_TIMEOUT,
                )?.let { return@withContext it.right() }
            }
            if (friend.internetDirectEnabled && friend.internetDirectGuestOptIn) {
                for (address in friend.directCandidates) {
                    openDirect(
                        route = ShareRoute.DIRECT_INTERNET,
                        address = address,
                        shareId = friend.shareId.toString(),
                        capability = friend.capability,
                        authMode = authMode,
                        timeout = INTERNET_TIMEOUT,
                    )?.let { return@withContext it.right() }
                }
            }
            GuestJoinFailure.NoRoute.left()
        }

    suspend fun probeLan(
        friend: SavedFriend,
        authMode: DirectP2pAuthMode,
        probe: FriendStatusProbe,
    ): ServerPresence? = withContext(ioDispatcher) {
        val discovered = matchingLanShare(friend)
            ?: return@withContext null
        val direct = openDirect(
            route = ShareRoute.DIRECT_LAN,
            address = discovered.lanAddress,
            shareId = friend.shareId.toString(),
            capability = friend.capability,
            authMode = authMode,
            timeout = LAN_TIMEOUT,
        ) ?: return@withContext null
        direct.use {
            probe.probe(direct.localAddress.statusAddress()).getOrNull()
        }
    }

    suspend fun probeDirect(
        friend: SavedFriend,
        authMode: DirectP2pAuthMode,
        probe: FriendStatusProbe,
    ): ServerPresence? = withContext(ioDispatcher) {
        matchingLanShare(friend)?.let { discovered ->
            val direct = openDirect(
                route = ShareRoute.DIRECT_LAN,
                address = discovered.lanAddress,
                shareId = friend.shareId.toString(),
                capability = friend.capability,
                authMode = authMode,
                timeout = LAN_TIMEOUT,
            )
            if (direct != null) {
                val presence = direct.use {
                    probe.probe(direct.localAddress.statusAddress()).getOrNull()
                }
                if (presence != null) {
                    return@withContext presence.copy(route = ShareRoute.DIRECT_LAN)
                }
            }
        }
        if (friend.internetDirectEnabled && friend.internetDirectGuestOptIn) {
            for (address in friend.directCandidates) {
                val direct = openDirect(
                    route = ShareRoute.DIRECT_INTERNET,
                    address = address,
                    shareId = friend.shareId.toString(),
                    capability = friend.capability,
                    authMode = authMode,
                    timeout = INTERNET_TIMEOUT,
                ) ?: continue
                val presence = direct.use {
                    probe.probe(direct.localAddress.statusAddress()).getOrNull()
                }
                if (presence != null) {
                    return@withContext presence.copy(
                        route = ShareRoute.DIRECT_INTERNET,
                    )
                }
            }
        }
        null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            node.close()
            mutableDiscovered.value = emptyList()
        }
    }

    private fun onDiscovered(discovered: DirectP2pDiscoveredShare) {
        val invitation = ShareInviteCodec.decode(
            discovered.invitation(),
            now(),
        ).getOrNull() ?: return
        if (invitation.payload.peerId != discovered.peerId()) {
            return
        }
        val found = DiscoveredLanShare(
            displayName = discovered.displayName(),
            invitationUri = discovered.invitation(),
            invitation = invitation,
            lanAddress = discovered.address(),
        )
        mutableDiscovered.value = (
            mutableDiscovered.value.filterNot {
                it.invitation.payload.shareId == invitation.payload.shareId
            } + found
        ).takeLast(MAX_DISCOVERED_SHARES)
    }

    private fun matchingLanAddress(
        invitation: SignedShareInvite,
    ): String? {
        val payload = invitation.payload
        return mutableDiscovered.value.firstOrNull {
            val discovered = it.invitation.payload
            discovered.shareId == payload.shareId &&
                discovered.peerId == payload.peerId
        }?.lanAddress
    }

    private fun matchingLanShare(friend: SavedFriend): DiscoveredLanShare? =
        mutableDiscovered.value.firstOrNull {
            val invitation = it.invitation
            val payload = invitation.payload
            payload.shareId == friend.shareId &&
                payload.peerId == friend.peerId &&
                payload.capability == friend.capability &&
                Base64.getEncoder().encodeToString(invitation.publicKey) ==
                friend.publicKeyBase64
        }

    private fun openDirect(
        route: ShareRoute,
        address: String,
        invitation: SignedShareInvite,
        authMode: DirectP2pAuthMode,
        timeout: Duration,
    ): GuestJoinTarget.Direct? = openDirect(
        route = route,
        address = address,
        shareId = invitation.payload.shareId.toString(),
        capability = invitation.payload.capability,
        authMode = authMode,
        timeout = timeout,
    )

    private fun openDirect(
        route: ShareRoute,
        address: String,
        shareId: String,
        capability: String,
        authMode: DirectP2pAuthMode,
        timeout: Duration,
    ): GuestJoinTarget.Direct? = try {
        val proxy = node.openProxy(
            address = address,
            shareId = shareId,
            capability = capability,
            authMode = authMode,
            timeout = timeout,
        )
        GuestJoinTarget.Direct(
            route = route,
            localAddress = proxy.localAddress(),
            proxy = proxy,
        )
    } catch (_: RuntimeException) {
        null
    }

    private fun reportRoute(message: String) {
        try {
            routeReporter(message)
        } catch (_: RuntimeException) {
            // Diagnostics must never alter route selection.
        }
    }

    private fun InetSocketAddress.statusAddress(): String {
        val host = hostString
        return if (host.contains(':')) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
    }

    companion object {
        internal fun testing(
            node: FabricGuestDirectNode,
            now: () -> Instant,
            ioDispatcher: CoroutineDispatcher,
            routeReporter: (String) -> Unit = {},
        ) = FabricShareBrowser(
            node,
            now,
            ioDispatcher,
            routeReporter,
        )

        private val LAN_TIMEOUT = Duration.ofSeconds(3)
        private val INTERNET_TIMEOUT = Duration.ofSeconds(5)
        private const val MAX_DISCOVERED_SHARES = 32
        private const val IDENTITY_FILE_NAME = "share-libp2p-identity.key"
        private val LOGGER = Logger.getLogger("Connect")
        private const val ROUTE_DIRECT_LAN =
            "Connect Share route: direct LAN"
        private const val ROUTE_DIRECT_LAN_UNAVAILABLE =
            "Connect Share route: direct LAN unavailable"
        private const val ROUTE_DIRECT_INTERNET =
            "Connect Share route: direct internet"
        private const val ROUTE_DIRECT_INTERNET_UNAVAILABLE =
            "Connect Share route: direct internet unavailable"
        private const val ROUTE_CONNECT_FALLBACK =
            "Connect Share route: using Connect fallback"
        private const val ROUTE_ENDPOINT_CONFLICT =
            "Connect Share route: blocked copied Connect endpoint"
    }
}

internal fun connectAddressesMatch(
    first: String?,
    second: String?,
): Boolean {
    val normalizedFirst = normalizeConnectAddress(first)
    val normalizedSecond = normalizeConnectAddress(second)
    return normalizedFirst != null && normalizedFirst == normalizedSecond
}

private fun normalizeConnectAddress(value: String?): String? =
    value
        ?.trim()
        ?.lowercase()
        ?.removeSuffix(".")
        ?.removeSuffix(":25565")
        ?.takeIf(String::isNotEmpty)

internal interface FabricGuestDirectNode : AutoCloseable {
    fun peerId(): String

    fun startDiscovery(listener: DirectP2pDiscoveryListener)

    fun openProxy(
        address: String,
        shareId: String,
        capability: String,
        authMode: DirectP2pAuthMode,
        timeout: Duration,
    ): DirectP2pProxy
}

private class CoreFabricGuestDirectNode(
    private val node: DirectP2pNode,
) : FabricGuestDirectNode {
    override fun peerId(): String = node.peerId()

    override fun startDiscovery(listener: DirectP2pDiscoveryListener) {
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
