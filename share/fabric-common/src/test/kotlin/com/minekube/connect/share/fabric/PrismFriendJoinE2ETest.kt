package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Opt-in bridge between the deterministic friend tests and a real Prism host
 * plus guest. See share/AGENTS.md for the launch sequence.
 */
class PrismFriendJoinE2ETest {
    @Test
    fun `saved friend requests and joins a live singleplayer world`() =
        runBlocking {
            val dataValue = System.getenv("LIVE_DATA")
            val portValue = System.getenv("LIVE_PORT_FILE")
            val hostLogValue = System.getenv("LIVE_HOST_LOG")
            assumeTrue(
                dataValue != null && portValue != null && hostLogValue != null,
                "LIVE_DATA, LIVE_PORT_FILE, and LIVE_HOST_LOG enable this E2E",
            )
            val dataDirectory = Path.of(checkNotNull(dataValue))
            val portFile = Path.of(checkNotNull(portValue))
            val hostLog = Path.of(checkNotNull(hostLogValue))
            val guestLog = System.getenv("LIVE_GUEST_LOG")?.let(Path::of)
            val playerName = System.getenv("LIVE_PLAYER_NAME") ?: "bob"
            val joinedLine = "] $playerName joined the game"
            val joinsBefore = Files.readString(hostLog)
                .lineSequence()
                .count { joinedLine in it }
            val guestLoadsBefore = guestLog?.let(::loadedAdvancementsCount)
            val friend = FriendStore(dataDirectory).all().single()
            System.getenv("LIVE_HOST_DATA")?.let { hostDataValue ->
                val guestPeerId = DirectP2pNode(
                    dataDirectory.resolve("share-libp2p-identity.key"),
                ).use(DirectP2pNode::peerId)
                assertTrue(
                    FriendStore(Path.of(hostDataValue)).relationship(guestPeerId)
                        .isSome(),
                    "The live host has not confirmed this guest peer identity",
                )
            }
            val browser = FabricShareBrowser(dataDirectory)
            try {
                assertTrue(browser.start().isRight())
                withTimeout(30_000) {
                    browser.discovered.first { discovered ->
                        discovered.any {
                            it.invitation.payload.peerId == friend.peerId
                        }
                    }
                }
                val client = FriendRequestClient()
                val activityTarget = browser.openFriendControl(
                    friend,
                    DirectP2pAuthMode.OFFLINE,
                ).getOrNull()!!
                val activityResult =
                    activityTarget.use {
                        client.activity(
                            it,
                            com.minekube.connect.share.friend
                                .FriendActivityRequest(UUID.randomUUID()),
                        )
                    }
                assertEquals(
                    FriendActivityKind.HOSTING_WORLD,
                    activityResult.getOrNull()?.kind
                        ?: fail(activityResult.leftOrNull()?.safeMessage
                            ?: "Host returned no friend activity"),
                )

                // Status and gameplay require different one-shot proxies.
                withTimeout(30_000) {
                    while (
                        browser.probeLan(
                            friend,
                            DirectP2pAuthMode.OFFLINE,
                            MinecraftStatusProbe(),
                        ) == null
                    ) {
                        delay(250)
                    }
                }
                val playerUuid = UUID.nameUUIDFromBytes(
                    "OfflinePlayer:$playerName".toByteArray(
                        StandardCharsets.UTF_8,
                    ),
                )
                val requestTarget = browser.openFriendControl(
                    friend,
                    DirectP2pAuthMode.OFFLINE,
                ).getOrNull()!!
                assertEquals(
                    FriendJoinApproval.SharedWorld,
                    requestTarget.use {
                        client.requestJoin(
                            it,
                            FriendJoinRequest(
                                UUID.randomUUID(),
                                playerName,
                                playerUuid,
                            ),
                        ).getOrNull()
                    },
                )
                val gameplay = assertIs<GuestJoinTarget.Direct>(
                    browser.join(
                        friend,
                        DirectP2pAuthMode.OFFLINE,
                    ).getOrNull(),
                )
                gameplay.use {
                    Files.writeString(
                        portFile,
                        gameplay.localAddress.port.toString(),
                    )
                    withTimeout(180_000) {
                        while (Files.readString(hostLog)
                                .lineSequence()
                                .count { joinedLine in it } <= joinsBefore
                        ) {
                            delay(100)
                        }
                    }
                    if (guestLog != null && guestLoadsBefore != null) {
                        withTimeout(180_000) {
                            while (
                                loadedAdvancementsCount(guestLog) <=
                                guestLoadsBefore
                            ) {
                                delay(100)
                            }
                        }
                    }
                }
            } finally {
                browser.close()
            }
        }

    private fun loadedAdvancementsCount(log: Path): Int =
        if (Files.exists(log)) {
            Files.readString(log).lineSequence().count {
                "Loaded " in it && " advancements" in it
            }
        } else {
            0
        }
}
