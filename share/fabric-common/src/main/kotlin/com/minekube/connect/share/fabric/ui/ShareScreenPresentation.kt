package com.minekube.connect.share.fabric.ui

import com.minekube.connect.share.fabric.FollowAction
import com.minekube.connect.share.friend.CompatibilityDifference
import com.minekube.connect.share.friend.FriendActivityKind

enum class FriendPresenceTone {
    JOINABLE,
    ONLINE,
    SAVED,
    OFFLINE,
}

enum class FriendPrimaryAction(
    val translationKey: String,
) {
    JOIN_NOW("connect_share.friends.action.join_now"),
    ASK_TO_JOIN("connect_share.friends.action.ask_to_join"),
    CANCEL_FOLLOW("connect_share.friends.action.cancel_follow"),
    JOIN_WHEN_READY("connect_share.friends.action.join_when_ready"),
}

data class FriendRowPresentation(
    val tone: FriendPresenceTone,
    val statusKey: String,
    val statusArguments: List<String>,
    val action: FriendPrimaryAction,
)

data class FriendsOverview(
    val friendCount: Int,
    val onlineCount: Int,
    val joinableCount: Int,
    val incomingCount: Int,
    val outgoingCount: Int,
)

enum class FriendsSummaryTone {
    ATTENTION,
    READY,
    ONLINE,
    MUTED,
}

data class FriendsSummaryPresentation(
    val translationKey: String,
    val count: Int?,
    val tone: FriendsSummaryTone,
)

data class MenuFriendsPresentation(
    val translationKey: String,
    val count: Int?,
)

data class CompatibilityLine(
    val translationKey: String,
    val arguments: List<String>,
)

data class FollowTerminalNotification(
    val titleKey: String,
    val detailKey: String,
    val displayName: String,
)

fun FollowAction.terminalNotification(): FollowTerminalNotification? =
    when (this) {
        is FollowAction.Expired -> FollowTerminalNotification(
            titleKey = "connect_share.notification.follow_expired",
            detailKey = "connect_share.notification.follow_expired_detail",
            displayName = displayName,
        )
        is FollowAction.Cancelled -> FollowTerminalNotification(
            titleKey = "connect_share.notification.follow_cancelled",
            detailKey = "connect_share.notification.follow_cancelled_detail",
            displayName = displayName,
        )
        is FollowAction.RequestJoin,
        is FollowAction.OfferJoinNow -> null
    }

fun FriendSummary.presentation(): FriendRowPresentation {
    val action = when {
        canJoinNow -> FriendPrimaryAction.JOIN_NOW
        canRequestJoin -> FriendPrimaryAction.ASK_TO_JOIN
        following -> FriendPrimaryAction.CANCEL_FOLLOW
        else -> FriendPrimaryAction.JOIN_WHEN_READY
    }
    val tone = when {
        canJoinNow || canRequestJoin ||
            activityKind == FriendActivityKind.HOSTING_WORLD ->
            FriendPresenceTone.JOINABLE

        activityKind == FriendActivityKind.PLAYING_SERVER ||
            activityKind == FriendActivityKind.ONLINE ||
            onlineViaLan || onlineViaConnect -> FriendPresenceTone.ONLINE

        connectAvailable -> FriendPresenceTone.SAVED
        else -> FriendPresenceTone.OFFLINE
    }
    val status = when {
        activityKind == FriendActivityKind.HOSTING_WORLD ->
            "connect_share.friends.status.world" to
                listOf(activityDescription ?: worldName ?: "Minecraft world")

        activityKind == FriendActivityKind.PLAYING_SERVER ->
            "connect_share.friends.status.server" to
                listOf(activityDescription ?: "Minecraft server")

        canJoinNow ->
            "connect_share.friends.status.ready" to emptyList()

        activityKind == FriendActivityKind.ONLINE || onlineViaConnect ->
            "connect_share.friends.status.online" to emptyList()

        connectAvailable ->
            "connect_share.friends.status.saved" to emptyList()

        else -> "connect_share.friends.status.offline" to emptyList()
    }
    return FriendRowPresentation(
        tone = tone,
        statusKey = status.first,
        statusArguments = status.second,
        action = action,
    )
}

fun FriendsUiState.overview(): FriendsOverview {
    val presentations = friends.map(FriendSummary::presentation)
    return FriendsOverview(
        friendCount = friends.size,
        onlineCount = presentations.count {
            it.tone == FriendPresenceTone.JOINABLE ||
                it.tone == FriendPresenceTone.ONLINE
        },
        joinableCount = presentations.count {
            it.tone == FriendPresenceTone.JOINABLE
        },
        incomingCount = incomingRequests.size,
        outgoingCount = outgoingRequests.size,
    )
}

fun FriendsOverview.summary(): FriendsSummaryPresentation {
    val baseKey: String
    val count: Int?
    val tone: FriendsSummaryTone
    when {
        incomingCount > 0 -> {
            baseKey = "connect_share.friends.summary.requests"
            count = incomingCount
            tone = FriendsSummaryTone.ATTENTION
        }
        friendCount == 0 -> return FriendsSummaryPresentation(
            translationKey = "connect_share.friends.summary.welcome",
            count = null,
            tone = FriendsSummaryTone.MUTED,
        )
        joinableCount > 0 -> {
            baseKey = "connect_share.friends.summary.joinable"
            count = joinableCount
            tone = FriendsSummaryTone.READY
        }
        onlineCount > 0 -> {
            baseKey = "connect_share.friends.summary.online"
            count = onlineCount
            tone = FriendsSummaryTone.ONLINE
        }
        else -> {
            baseKey = "connect_share.friends.summary.saved"
            count = friendCount
            tone = FriendsSummaryTone.MUTED
        }
    }
    return FriendsSummaryPresentation(
        translationKey = "$baseKey.${if (count == 1) "one" else "many"}",
        count = count,
        tone = tone,
    )
}

fun FriendsOverview.menuLabel(): MenuFriendsPresentation = when {
    incomingCount > 0 -> MenuFriendsPresentation(
        translationKey = "connect_share.menu.requests",
        count = incomingCount,
    )
    joinableCount > 0 -> MenuFriendsPresentation(
        translationKey = "connect_share.menu.ready",
        count = joinableCount,
    )
    else -> MenuFriendsPresentation(
        translationKey = "connect_share.menu.join",
        count = null,
    )
}

fun CompatibilityDifference.presentation(): CompatibilityLine = when (this) {
    is CompatibilityDifference.MinecraftVersion -> CompatibilityLine(
        "connect_share.compatibility.minecraft",
        listOf(local, remote),
    )

    is CompatibilityDifference.Loader -> CompatibilityLine(
        "connect_share.compatibility.loader",
        listOf(local.name.lowercase(), remote.name.lowercase()),
    )

    is CompatibilityDifference.MissingLocal -> CompatibilityLine(
        "connect_share.compatibility.install",
        listOf(modId, remoteVersion),
    )

    is CompatibilityDifference.MissingRemote -> CompatibilityLine(
        "connect_share.compatibility.host_missing",
        listOf(modId, localVersion),
    )

    is CompatibilityDifference.ModVersion -> CompatibilityLine(
        "connect_share.compatibility.mod_version",
        listOf(modId, local, remote),
    )
}
