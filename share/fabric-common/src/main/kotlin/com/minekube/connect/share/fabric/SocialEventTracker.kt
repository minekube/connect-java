package com.minekube.connect.share.fabric

import com.minekube.connect.share.fabric.ui.FriendSummary
import com.minekube.connect.share.fabric.ui.FriendsUiState
import com.minekube.connect.share.friend.FriendActivityKind

sealed interface SocialEvent {
    val displayName: String

    data class FriendAccepted(
        override val displayName: String,
    ) : SocialEvent

    data class FriendRemoved(
        override val displayName: String,
    ) : SocialEvent

    data class PlayingServer(
        override val displayName: String,
        val serverName: String,
    ) : SocialEvent

    data class WorldReady(
        override val displayName: String,
        val worldName: String?,
    ) : SocialEvent
}

class SocialEventTracker {
    private var previous: Map<String, FriendSummary>? = null

    fun update(state: FriendsUiState): List<SocialEvent> {
        val current = state.friends.associateBy(FriendSummary::peerId)
        val before = previous
        previous = current
        if (before == null) return emptyList()

        val events = mutableListOf<SocialEvent>()
        current.values.forEach { friend ->
            val old = before[friend.peerId]
            when {
                old == null -> events +=
                    SocialEvent.FriendAccepted(friend.displayName)

                friend.activityKind == FriendActivityKind.PLAYING_SERVER &&
                    old.activityKind != FriendActivityKind.PLAYING_SERVER ->
                    events += SocialEvent.PlayingServer(
                        friend.displayName,
                        friend.activityDescription ?: "Minecraft server",
                    )

                friend.canJoinNow && !old.canJoinNow ->
                    events += SocialEvent.WorldReady(
                        friend.displayName,
                        friend.worldName,
                    )
            }
        }
        before.values
            .filter { it.peerId !in current }
            .forEach {
                events += SocialEvent.FriendRemoved(it.displayName)
            }
        return events
    }
}
