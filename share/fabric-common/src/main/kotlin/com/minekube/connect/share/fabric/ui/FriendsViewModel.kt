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
import com.minekube.connect.share.fabric.FollowAction
import com.minekube.connect.share.fabric.FollowNextSessionController
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.SavedFriend
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.CompatibilityProfile
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
    val activityKind: FriendActivityKind? = null,
    val activityDescription: String? = null,
    val canRequestJoin: Boolean = false,
    val canJoinNow: Boolean = false,
    val following: Boolean = false,
)

data class OutgoingFriendRequestSummary(
    val peerId: String,
    val displayName: String,
    val relationshipId: UUID = UUID.randomUUID(),
)

data class IncomingFriendRequestSummary(
    val requestId: UUID,
    val displayName: String,
    val ingress: Ingress,
    val purpose: AdmissionPurpose,
)

data class BlockedFriendSummary(
    val peerId: String,
    val displayName: String,
)

data class FriendsUiState(
    val friends: List<FriendSummary> = emptyList(),
    val outgoingRequests: List<OutgoingFriendRequestSummary> = emptyList(),
    val incomingRequests: List<IncomingFriendRequestSummary> = emptyList(),
    val blocked: List<BlockedFriendSummary> = emptyList(),
    val safeMessage: String? = null,
)

class FriendsViewModel(
    private val store: FriendStore,
    private val followController: FollowNextSessionController =
        FollowNextSessionController(),
    private val onPeerRemoved: (String) -> Unit = {},
    private val onRemovalQueued: () -> Unit = {},
) {
    private var discovered: List<DiscoveredLanShare> = emptyList()
    private var remotePresence: Map<String, RemoteFriendPresence> = emptyMap()
    private var activities: Map<String, FriendActivity> = emptyMap()
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
                if (removed) {
                    notifyPeerRemoved(peerId)
                    onRemovalQueued()
                }
                removed
            },
        )

    fun block(peerId: String): Boolean =
        Either.catch { store.block(peerId) }.fold(
            ifLeft = {
                update { copy(safeMessage = FRIEND_BLOCK_FAILURE) }
                false
            },
            ifRight = { blocked ->
                refresh()
                if (blocked) {
                    notifyPeerRemoved(peerId)
                    onRemovalQueued()
                }
                blocked
            },
        )

    fun unblock(peerId: String): Boolean =
        Either.catch { store.unblock(peerId) }.fold(
            ifLeft = {
                update { copy(safeMessage = FRIEND_UNBLOCK_FAILURE) }
                false
            },
            ifRight = { unblocked ->
                refresh()
                unblocked
            },
        )

    fun follow(peerId: String): Boolean {
        val friend = savedFriend(peerId) ?: return false
        followController.follow(peerId, friend.displayName)
        refresh(preserveSafeMessage = true)
        return true
    }

    fun cancelFollow(peerId: String): Boolean =
        followController.cancel(peerId).also {
            if (it) refresh(preserveSafeMessage = true)
        }

    fun completeFollow(peerId: String): Boolean =
        followController.complete(peerId).also {
            if (it) refresh(preserveSafeMessage = true)
        }

    fun followActions(activeGameplay: Boolean): List<FollowAction> =
        followController.update(
            activities = activities,
            activeGameplay = activeGameplay,
            confirmedPeerIds = runCatching {
                store.all().mapTo(mutableSetOf(), SavedFriend::peerId)
            }.getOrDefault(emptySet()),
        ).also {
            if (it.isNotEmpty()) refresh(preserveSafeMessage = true)
        }

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

    fun updateActivities(activity: Map<String, FriendActivity>) {
        if (activities == activity) return
        activities = activity
        refresh(preserveSafeMessage = true)
    }

    fun updateIncoming(pending: List<PendingAdmission>) {
        val next = pending
            .asSequence()
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
                    purpose = it.purpose,
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

    suspend fun routeFriendControl(
        peerId: String,
        browser: FabricShareBrowser,
        authMode: DirectP2pAuthMode,
    ): Either<GuestJoinFailure, GuestJoinTarget.Direct> {
        val friend = savedFriend(peerId)
            ?: return GuestJoinFailure.NoRoute.left()
        return browser.openFriendControl(friend, authMode)
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

    internal fun compatibilityFor(peerId: String): CompatibilityProfile? =
        activities[peerId]?.compatibility

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
                    relationshipId = it.relationshipId,
                )
            },
            incomingRequests = incomingRequests,
            blocked = store.blocked().map {
                BlockedFriendSummary(
                    peerId = it.peerId,
                    displayName = it.displayName,
                )
            },
        )

    private fun update(transform: FriendsUiState.() -> FriendsUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private fun notifyPeerRemoved(peerId: String) {
        try {
            onPeerRemoved(peerId)
        } catch (_: RuntimeException) {
        }
    }

    private fun SavedFriend.summary(): FriendSummary {
        val remote = remotePresence[peerId]
            ?.takeIf { it.online }
        val activity = activities[peerId]
        return FriendSummary(
            peerId = peerId,
            displayName = displayName,
            connectAvailable = connectAddress != null,
            permissions = permissions,
            onlineViaLan = remote?.route == ShareRoute.DIRECT_LAN,
            onlineViaConnect = remote?.route == ShareRoute.CONNECT,
            worldName = remote?.description,
            activityKind = activity?.kind,
            activityDescription = activity?.description,
            canRequestJoin = activity?.joinable == true &&
                (
                    activity.kind == FriendActivityKind.PLAYING_SERVER ||
                        activity.kind == FriendActivityKind.HOSTING_WORLD &&
                        remote != null
                    ),
            canJoinNow = remote != null &&
                activity?.kind != FriendActivityKind.PLAYING_SERVER &&
                activity?.kind != FriendActivityKind.HOSTING_WORLD,
            following = peerId in followController.state.value,
        )
    }

    private companion object {
        const val FRIENDS_LOAD_FAILURE =
            "Saved Connect Share friends could not be loaded"
        const val FRIEND_REMOVE_FAILURE =
            "This Connect Share friend could not be removed"
        const val FRIEND_BLOCK_FAILURE =
            "This Connect Share identity could not be blocked"
        const val FRIEND_UNBLOCK_FAILURE =
            "This Connect Share identity could not be unblocked"
    }
}
