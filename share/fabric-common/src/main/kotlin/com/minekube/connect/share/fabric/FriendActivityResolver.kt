package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.CompatibilityProfile

object FriendActivityResolver {
    fun resolve(
        worldAvailable: Boolean,
        worldSharingActive: Boolean,
        worldName: String?,
        externalServerName: String?,
        sessionEpoch: String? = null,
        compatibility: CompatibilityProfile? = null,
    ): FriendActivity = when {
        externalServerName != null -> FriendActivity(
            FriendActivityKind.PLAYING_SERVER,
            externalServerName,
            sessionEpoch = sessionEpoch,
            compatibility = compatibility,
        )
        worldAvailable && worldSharingActive -> FriendActivity(
            FriendActivityKind.HOSTING_WORLD,
            worldName?.takeIf(String::isNotBlank) ?: "Minecraft world",
            sessionEpoch = sessionEpoch,
            compatibility = compatibility,
        )
        else -> FriendActivity(
            FriendActivityKind.ONLINE,
            compatibility = compatibility,
        )
    }
}
