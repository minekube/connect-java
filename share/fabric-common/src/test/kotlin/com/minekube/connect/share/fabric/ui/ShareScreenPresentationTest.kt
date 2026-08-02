package com.minekube.connect.share.fabric.ui

import com.minekube.connect.share.friend.CompatibilityDifference
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendPermissions
import com.minekube.connect.share.friend.ModLoader
import kotlin.test.Test
import kotlin.test.assertEquals

class ShareScreenPresentationTest {
    @Test
    fun `joinable world is the strongest friend state`() {
        val friend = friend(
            activityKind = FriendActivityKind.HOSTING_WORLD,
            activityDescription = "Cherry Grove",
            canRequestJoin = true,
            connectAvailable = true,
        )

        val presentation = friend.presentation()

        assertEquals(FriendPresenceTone.JOINABLE, presentation.tone)
        assertEquals(FriendPrimaryAction.ASK_TO_JOIN, presentation.action)
        assertEquals("connect_share.friends.status.world", presentation.statusKey)
        assertEquals(listOf("Cherry Grove"), presentation.statusArguments)
    }

    @Test
    fun `approved friend can join now without another request`() {
        val presentation = friend(
            canJoinNow = true,
            onlineViaLan = true,
        ).presentation()

        assertEquals(FriendPresenceTone.JOINABLE, presentation.tone)
        assertEquals(FriendPrimaryAction.JOIN_NOW, presentation.action)
    }

    @Test
    fun `following an unavailable friend offers cancellation`() {
        val presentation = friend(following = true).presentation()

        assertEquals(FriendPresenceTone.OFFLINE, presentation.tone)
        assertEquals(FriendPrimaryAction.CANCEL_FOLLOW, presentation.action)
    }

    @Test
    fun `friends overview counts only real friends as online or joinable`() {
        val state = FriendsUiState(
            friends = listOf(
                friend(activityKind = FriendActivityKind.ONLINE),
                friend(canRequestJoin = true),
                friend(),
            ),
            incomingRequests = listOf(
                IncomingFriendRequestSummary(
                    requestId = java.util.UUID.randomUUID(),
                    displayName = "Alex",
                    ingress = com.minekube.connect.share.admission.Ingress.DIRECT_LAN,
                    purpose = com.minekube.connect.share.admission.AdmissionPurpose.FRIEND,
                ),
            ),
            outgoingRequests = listOf(
                OutgoingFriendRequestSummary("pending", "Sam"),
            ),
        )

        assertEquals(
            FriendsOverview(
                friendCount = 3,
                onlineCount = 2,
                joinableCount = 1,
                incomingCount = 1,
                outgoingCount = 1,
            ),
            state.overview(),
        )
    }

    @Test
    fun `friends summary prioritizes requests and uses singular copy`() {
        val presentation = FriendsOverview(
            friendCount = 4,
            onlineCount = 3,
            joinableCount = 2,
            incomingCount = 1,
            outgoingCount = 0,
        ).summary()

        assertEquals(
            "connect_share.friends.summary.requests.one",
            presentation.translationKey,
        )
        assertEquals(FriendsSummaryTone.ATTENTION, presentation.tone)
        assertEquals(1, presentation.count)
    }

    @Test
    fun `friends summary uses plural copy for the strongest available state`() {
        val presentation = FriendsOverview(
            friendCount = 4,
            onlineCount = 3,
            joinableCount = 2,
            incomingCount = 0,
            outgoingCount = 0,
        ).summary()

        assertEquals(
            "connect_share.friends.summary.joinable.many",
            presentation.translationKey,
        )
        assertEquals(FriendsSummaryTone.READY, presentation.tone)
        assertEquals(2, presentation.count)
    }

    @Test
    fun `empty friends summary welcomes instead of counting zero`() {
        val presentation = FriendsOverview(
            friendCount = 0,
            onlineCount = 0,
            joinableCount = 0,
            incomingCount = 0,
            outgoingCount = 0,
        ).summary()

        assertEquals(
            "connect_share.friends.summary.welcome",
            presentation.translationKey,
        )
        assertEquals(null, presentation.count)
    }

    @Test
    fun `menu label surfaces requests before ready friends`() {
        val requests = FriendsOverview(5, 4, 3, 2, 0).menuLabel()
        val ready = FriendsOverview(5, 4, 3, 0, 0).menuLabel()
        val quiet = FriendsOverview(5, 0, 0, 0, 0).menuLabel()

        assertEquals("connect_share.menu.requests", requests.translationKey)
        assertEquals(2, requests.count)
        assertEquals("connect_share.menu.ready", ready.translationKey)
        assertEquals(3, ready.count)
        assertEquals("connect_share.menu.join", quiet.translationKey)
        assertEquals(null, quiet.count)
    }

    @Test
    fun `compatibility details use localizable semantic lines`() {
        val lines = listOf(
            CompatibilityDifference.MinecraftVersion("26.2", "1.21.11"),
            CompatibilityDifference.Loader(ModLoader.FABRIC, ModLoader.NEOFORGE),
            CompatibilityDifference.MissingLocal("create", "6.0"),
            CompatibilityDifference.MissingRemote("sodium", "0.9"),
            CompatibilityDifference.ModVersion("voicechat", "2", "3"),
        ).map(CompatibilityDifference::presentation)

        assertEquals(
            listOf(
                "connect_share.compatibility.minecraft",
                "connect_share.compatibility.loader",
                "connect_share.compatibility.install",
                "connect_share.compatibility.host_missing",
                "connect_share.compatibility.mod_version",
            ),
            lines.map(CompatibilityLine::translationKey),
        )
        assertEquals(listOf("26.2", "1.21.11"), lines.first().arguments)
    }

    private fun friend(
        connectAvailable: Boolean = false,
        onlineViaLan: Boolean = false,
        onlineViaConnect: Boolean = false,
        activityKind: FriendActivityKind? = null,
        activityDescription: String? = null,
        canRequestJoin: Boolean = false,
        canJoinNow: Boolean = false,
        following: Boolean = false,
    ): FriendSummary = FriendSummary(
        peerId = "peer",
        displayName = "Robin",
        connectAvailable = connectAvailable,
        permissions = FriendPermissions(),
        onlineViaLan = onlineViaLan,
        onlineViaConnect = onlineViaConnect,
        activityKind = activityKind,
        activityDescription = activityDescription,
        canRequestJoin = canRequestJoin,
        canJoinNow = canJoinNow,
        following = following,
    )
}
