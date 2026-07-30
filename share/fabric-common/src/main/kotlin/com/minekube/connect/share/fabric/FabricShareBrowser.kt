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
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveredShare
import com.minekube.connect.tunnel.p2p.DirectP2pDiscoveryListener
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import com.minekube.connect.tunnel.p2p.DirectP2pProxy
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
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
}

class FabricShareBrowser private constructor(
    private val node: FabricGuestDirectNode,
    private val now: () -> Instant,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable {
    constructor() : this(
        node = CoreFabricGuestDirectNode(DirectP2pNode()),
        now = Instant::now,
        ioDispatcher = Dispatchers.IO,
    )

    private val mutableDiscovered =
        MutableStateFlow<List<DiscoveredLanShare>>(emptyList())
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    val discovered: StateFlow<List<DiscoveredLanShare>> =
        mutableDiscovered.asStateFlow()

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
                        openDirect(
                            route,
                            address,
                            invitation,
                            authMode,
                            LAN_TIMEOUT,
                        )?.let { return@withContext it.right() }
                    }

                    ShareRoute.DIRECT_INTERNET -> {
                        for (address in payload.directCandidates) {
                            openDirect(
                                route,
                                address,
                                invitation,
                                authMode,
                                INTERNET_TIMEOUT,
                            )?.let { return@withContext it.right() }
                        }
                    }

                    ShareRoute.CONNECT -> {
                        payload.connectAddress?.let {
                            return@withContext GuestJoinTarget.Connect(it).right()
                        }
                    }
                }
            }
            GuestJoinFailure.NoRoute.left()
        }
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

    private fun openDirect(
        route: ShareRoute,
        address: String,
        invitation: SignedShareInvite,
        authMode: DirectP2pAuthMode,
        timeout: Duration,
    ): GuestJoinTarget.Direct? = try {
        val payload = invitation.payload
        val proxy = node.openProxy(
            address = address,
            shareId = payload.shareId.toString(),
            capability = payload.capability,
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

    companion object {
        internal fun testing(
            node: FabricGuestDirectNode,
            now: () -> Instant,
            ioDispatcher: CoroutineDispatcher,
        ) = FabricShareBrowser(node, now, ioDispatcher)

        private val LAN_TIMEOUT = Duration.ofSeconds(3)
        private val INTERNET_TIMEOUT = Duration.ofSeconds(5)
        private const val MAX_DISCOVERED_SHARES = 32
    }
}

internal interface FabricGuestDirectNode : AutoCloseable {
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
