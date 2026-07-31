package com.minekube.connect.share.fabric.ui

import arrow.core.Either
import arrow.core.Option
import arrow.core.left
import arrow.core.toOption
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.admission.PendingAdmission
import com.minekube.connect.share.fabric.DiscoveredLanShare
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.GuestJoinFailure
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.fabric.RemoteFriendPresence
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.SavedFriend
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FriendSummary(
    val peerId: String,
    val displayName: String,
    val connectAvailable: Boolean,
    val permissions: FriendPermissions,
    val onlineViaLan: Boolean = false,
    val onlineViaConnect: Boolean = false,
    val worldName: String? = null,
)

data class OutgoingFriendRequestSummary(
    val peerId: String,
    val displayName: String,
)

data class IncomingFriendRequestSummary(
    val requestId: UUID,
    val displayName: String,
    val ingress: Ingress,
)

data class FriendsUiState(
    val friends: List<FriendSummary> = emptyList(),
    val outgoingRequests: List<OutgoingFriendRequestSummary> = emptyList(),
    val incomingRequests: List<IncomingFriendRequestSummary> = emptyList(),
    val safeMessage: String? = null,
)

class FriendsViewModel(
    private val store: FriendStore,
) {
    private var discovered: List<DiscoveredLanShare> = emptyList()
    private var remotePresence: Map<String, RemoteFriendPresence> = emptyMap()
    private var incomingRequests: List<IncomingFriendRequestSummary> =
        emptyList()
    private val mutableState = MutableStateFlow(loadInitialState())

    val state: StateFlow<FriendsUiState> = mutableState.asStateFlow()

    fun sendRequest(
        invitationUri: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): String? =
        store.sendRequest(invitationUri, displayName, now).fold(
            ifLeft = { failure ->
                update { copy(safeMessage = failure.safeMessage) }
                null
            },
            ifRight = { request ->
                refresh()
                request.peerId
            },
        )

    fun suggestedDisplayName(
        invitationUri: String,
        now: Instant = Instant.now(),
    ): Option<String> = ShareInviteCodec.decode(
        invitationUri.trim(),
        now,
    ).getOrNull()?.payload?.displayName.toOption()

    fun rename(peerId: String, displayName: String) {
        store.rename(peerId, displayName).fold(
            ifLeft = { failure ->
                update { copy(safeMessage = failure.safeMessage) }
            },
            ifRight = {
                refresh()
            },
        )
    }

    fun updatePermissions(
        peerId: String,
        permissions: FriendPermissions,
    ) {
        store.updatePermissions(peerId, permissions).fold(
            ifLeft = { failure ->
                update { copy(safeMessage = failure.safeMessage) }
            },
            ifRight = {
                refresh()
            },
        )
    }

    fun remove(peerId: String): Boolean =
        Either.catch {
            store.remove(peerId)
        }.fold(
            ifLeft = {
                update { copy(safeMessage = FRIEND_REMOVE_FAILURE) }
                false
            },
            ifRight = { removed ->
                refresh()
                removed
            },
        )

    fun updatePresence(discovered: List<DiscoveredLanShare>) {
        if (this.discovered == discovered) {
            return
        }
        this.discovered = discovered
        refresh(preserveSafeMessage = true)
    }

    fun updateRemotePresence(
        presence: Map<String, RemoteFriendPresence>,
    ) {
        if (remotePresence == presence) {
            return
        }
        remotePresence = presence
        refresh(preserveSafeMessage = true)
    }

    fun updateIncoming(pending: List<PendingAdmission>) {
        val next = pending
            .asSequence()
            .filter { it.purpose == AdmissionPurpose.FRIEND }
            .map {
                IncomingFriendRequestSummary(
                    requestId = it.requestId,
                    displayName = it.identity.name,
                    ingress = when (val identity = it.identity) {
                        is AdmissionIdentity.Authenticated ->
                            identity.ingress

                        is AdmissionIdentity.UnverifiedOffline ->
                            identity.ingress
                    },
                )
            }
            .toList()
        if (incomingRequests == next) {
            refresh(preserveSafeMessage = true)
            return
        }
        incomingRequests = next
        refresh(preserveSafeMessage = true)
    }

    suspend fun join(
        peerId: String,
        browser: FabricShareBrowser,
        authMode: DirectP2pAuthMode,
        ownConnectAddress: String? = null,
    ): Either<GuestJoinFailure, GuestJoinTarget> {
        val friend = savedFriend(peerId)
            ?: return GuestJoinFailure.NoRoute.left()
        return browser.join(friend, authMode, ownConnectAddress)
    }

    suspend fun routeOutgoing(
        peerId: String,
        browser: FabricShareBrowser,
        authMode: DirectP2pAuthMode,
    ): Either<GuestJoinFailure, GuestJoinTarget.Direct> {
        val request = outgoingRequest(peerId)
            ?: return GuestJoinFailure.NoRoute.left()
        return browser.openFriendControl(request, authMode)
    }

    fun reload() {
        refresh()
    }

    internal fun savedFriend(peerId: String): SavedFriend? =
        runCatching {
            store.all().firstOrNull { it.peerId == peerId }
        }.getOrNull()

    internal fun outgoingRequest(peerId: String): SavedFriend? =
        runCatching {
            store.outgoingRequests().firstOrNull {
                it.peerId == peerId
            }
        }.getOrNull()

    private fun refresh(
        preserveSafeMessage: Boolean = false,
    ) {
        mutableState.value = try {
            currentState().let { next ->
                if (preserveSafeMessage) {
                    next.copy(
                        safeMessage =
                            mutableState.value.safeMessage,
                    )
                } else {
                    next
                }
            }
        } catch (_: Exception) {
            mutableState.value.copy(
                safeMessage = FRIENDS_LOAD_FAILURE,
            )
        }
    }

    private fun loadInitialState(): FriendsUiState = try {
        currentState()
    } catch (_: Exception) {
        FriendsUiState(safeMessage = FRIENDS_LOAD_FAILURE)
    }

    private fun currentState(): FriendsUiState =
        FriendsUiState(
            friends = store.all().map { it.summary() },
            outgoingRequests = store.outgoingRequests().map {
                OutgoingFriendRequestSummary(
                    peerId = it.peerId,
                    displayName = it.displayName,
                )
            },
            incomingRequests = incomingRequests,
        )

    private fun update(transform: FriendsUiState.() -> FriendsUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private fun SavedFriend.summary(): FriendSummary {
        val remote = remotePresence[peerId]
            ?.takeIf { it.online }
        return FriendSummary(
            peerId = peerId,
            displayName = displayName,
            connectAvailable = connectAddress != null,
            permissions = permissions,
            onlineViaLan = remote?.route == ShareRoute.DIRECT_LAN,
            onlineViaConnect = remote?.route == ShareRoute.CONNECT,
            worldName = remote?.description,
        )
    }

    private companion object {
        const val FRIENDS_LOAD_FAILURE =
            "Saved Connect Share friends could not be loaded"
        const val FRIEND_REMOVE_FAILURE =
            "This Connect Share friend could not be removed"
    }
}
