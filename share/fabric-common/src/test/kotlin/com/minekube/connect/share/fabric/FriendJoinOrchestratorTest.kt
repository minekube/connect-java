package com.minekube.connect.share.fabric

import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.CompatibilityProfile
import com.minekube.connect.share.friend.ModLoader
import com.minekube.connect.share.friend.RequiredMod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class FriendJoinOrchestratorTest {
    @Test
    fun `external server approval becomes a normal Connect destination`() = runTest {
        val orchestrator = FriendJoinOrchestrator.testing(
            requestApproval = {
                FriendJoinApproval.ExternalServer("friends.example.test").right()
            },
            openSharedWorld = { error("shared route must not open") },
        )

        val result = orchestrator.request(PEER_ID, REQUEST).getOrNull()

        assertEquals(
            GuestJoinTarget.Connect("friends.example.test"),
            result,
        )
    }

    @Test
    fun `shared world opens gameplay only after approval`() = runTest {
        var openedPeer: String? = null
        val expected = GuestJoinTarget.Connect("shared.example.test")
        val orchestrator = FriendJoinOrchestrator.testing(
            requestApproval = { FriendJoinApproval.SharedWorld.right() },
            openSharedWorld = { peerId ->
                openedPeer = peerId
                expected.right()
            },
        )

        val result = orchestrator.request(PEER_ID, REQUEST).getOrNull()

        assertEquals(PEER_ID, openedPeer)
        assertEquals(expected, result)
    }

    @Test
    fun `approval failure stays actionable and never opens gameplay`() = runTest {
        val orchestrator = FriendJoinOrchestrator.testing(
            requestApproval = { FriendRequestFailure.Unreachable.left() },
            openSharedWorld = { error("gameplay must not open") },
        )

        val failure = orchestrator.request(PEER_ID, REQUEST).leftOrNull()

        assertIs<FriendJoinAttemptFailure.Request>(failure)
        assertEquals(
            "Your friend is not reachable right now",
            failure.safeMessage,
        )
    }

    @Test
    fun `incompatible Minecraft version blocks before requesting approval`() = runTest {
        var approvalRequested = false
        val orchestrator = FriendJoinOrchestrator.testing(
            requestApproval = {
                approvalRequested = true
                FriendJoinApproval.SharedWorld.right()
            },
            openSharedWorld = { error("gameplay must not open") },
            localCompatibility = { profile("1.21.1") },
            remoteCompatibility = { profile("1.20.1") },
        )

        val failure = orchestrator.request(PEER_ID, REQUEST).leftOrNull()

        assertIs<FriendJoinAttemptFailure.Compatibility>(failure)
        assertEquals("Your Minecraft versions do not match.", failure.safeMessage)
        assertEquals(false, failure.canTryAnyway)
        assertEquals(false, approvalRequested)
    }

    @Test
    fun `mod mismatch requires explicit try anyway before approval`() = runTest {
        var approvals = 0
        val orchestrator = FriendJoinOrchestrator.testing(
            requestApproval = {
                approvals++
                FriendJoinApproval.ExternalServer("friends.example.test").right()
            },
            openSharedWorld = { error("gameplay must not open") },
            localCompatibility = { profile(modVersion = "1") },
            remoteCompatibility = { profile(modVersion = "2") },
        )

        val blocked = orchestrator.request(PEER_ID, REQUEST).leftOrNull()
        assertIs<FriendJoinAttemptFailure.Compatibility>(blocked)
        assertEquals(true, blocked.canTryAnyway)
        assertEquals(0, approvals)

        val allowed = orchestrator.request(
            PEER_ID,
            REQUEST,
            allowModMismatch = true,
        ).getOrNull()
        assertEquals(
            GuestJoinTarget.Connect("friends.example.test"),
            allowed,
        )
        assertEquals(1, approvals)
    }

    private fun profile(
        minecraft: String = "1.21.1",
        modVersion: String = "1",
    ) = CompatibilityProfile(
        minecraftVersion = minecraft,
        loader = ModLoader.FABRIC,
        requiredMods = listOf(RequiredMod("example", modVersion)),
    )

    private companion object {
        const val PEER_ID = "12D3KooWRobin"
        val REQUEST = FriendJoinRequest(
            requestId = java.util.UUID.randomUUID(),
            playerName = "Alex",
            playerUuid = java.util.UUID.randomUUID(),
        )
    }
}
