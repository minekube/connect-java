package com.minekube.connect.share.fabric.ui

import arrow.core.Either
import arrow.core.left
import com.minekube.connect.share.fabric.DiscoveredLanShare
import com.minekube.connect.share.fabric.FabricShareBrowser
import com.minekube.connect.share.fabric.GuestJoinFailure
import com.minekube.connect.share.fabric.GuestJoinTarget
import com.minekube.connect.share.fabric.RemoteFriendPresence
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.SavedFriend
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import java.time.Instant
import java.util.Base64
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

data class FriendsUiState(
    val friends: List<FriendSummary> = emptyList(),
    val safeMessage: String? = null,
)

class FriendsViewModel(
    private val store: FriendStore,
) {
    private var discovered: List<DiscoveredLanShare> = emptyList()
    private var remotePresence: Map<String, RemoteFriendPresence> = emptyMap()
    private val mutableState = MutableStateFlow(loadInitialState())

    val state: StateFlow<FriendsUiState> = mutableState.asStateFlow()

    fun accept(
        invitationUri: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): Boolean =
        store.accept(invitationUri, displayName, now).fold(
            ifLeft = { failure ->
                update { copy(safeMessage = failure.safeMessage) }
                false
            },
            ifRight = {
                refresh()
                true
            },
        )

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

    fun remove(peerId: String) {
        if (store.remove(peerId)) {
            refresh()
        }
    }

    fun updatePresence(discovered: List<DiscoveredLanShare>) {
        this.discovered = discovered
        refresh()
    }

    fun updateRemotePresence(
        presence: Map<String, RemoteFriendPresence>,
    ) {
        remotePresence = presence
        refresh()
    }

    suspend fun join(
        peerId: String,
        browser: FabricShareBrowser,
        authMode: DirectP2pAuthMode,
    ): Either<GuestJoinFailure, GuestJoinTarget> {
        val friend = savedFriend(peerId)
            ?: return GuestJoinFailure.NoRoute.left()
        return browser.join(friend, authMode)
    }

    internal fun savedFriend(peerId: String): SavedFriend? =
        runCatching {
            store.all().firstOrNull { it.peerId == peerId }
        }.getOrNull()

    private fun refresh() {
        mutableState.value = try {
            FriendsUiState(friends = store.all().map { it.summary() })
        } catch (_: Exception) {
            mutableState.value.copy(
                safeMessage = FRIENDS_LOAD_FAILURE,
            )
        }
    }

    private fun loadInitialState(): FriendsUiState = try {
        FriendsUiState(friends = store.all().map { it.summary() })
    } catch (_: Exception) {
        FriendsUiState(safeMessage = FRIENDS_LOAD_FAILURE)
    }

    private fun update(transform: FriendsUiState.() -> FriendsUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private fun SavedFriend.summary(): FriendSummary {
        val presence = discovered.firstOrNull {
            val invitation = it.invitation
            invitation.payload.peerId == peerId &&
                invitation.payload.shareId == shareId &&
                Base64.getEncoder().encodeToString(invitation.publicKey) ==
                publicKeyBase64
        }
        val remote = remotePresence[peerId]
            ?.takeIf { it.online }
        return FriendSummary(
            peerId = peerId,
            displayName = displayName,
            connectAvailable = connectAddress != null,
            permissions = permissions,
            onlineViaLan = presence != null,
            onlineViaConnect = remote != null,
            worldName = presence?.displayName ?: remote?.description,
        )
    }

    private companion object {
        const val FRIENDS_LOAD_FAILURE =
            "Saved Connect Share friends could not be loaded"
    }
}
