package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind

object FriendActivityResolver {
    fun resolve(
        worldAvailable: Boolean,
        worldSharingActive: Boolean,
        worldName: String?,
        externalServerName: String?,
    ): FriendActivity = when {
        externalServerName != null -> FriendActivity(
            FriendActivityKind.PLAYING_SERVER,
            externalServerName,
        )
        worldAvailable && worldSharingActive -> FriendActivity(
            FriendActivityKind.HOSTING_WORLD,
            worldName?.takeIf(String::isNotBlank) ?: "Minecraft world",
        )
        else -> FriendActivity(FriendActivityKind.ONLINE)
    }
}
