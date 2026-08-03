package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in bridge between the deterministic friend tests and a real Prism host
 * plus guest. See share/AGENTS.md for the launch sequence.
 */
class PrismFriendJoinE2ETest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `rotated guest log counts fresh advancement evidence`() {
        val guestLog = tempDir.resolve("latest.log")
        Files.writeString(
            guestLog,
            "[old] [Render thread/INFO]: Loaded 41 advancements\n",
        )
        val beforeAppend = snapshotLog(guestLog)

        Files.writeString(
            guestLog,
            "[old] [Render thread/INFO]: Loaded 41 advancements\n" +
                "[new] [Render thread/INFO]: Loaded 41 advancements\n",
        )

        assertTrue(hasNewLoadedAdvancements(guestLog, beforeAppend))

        Files.writeString(
            guestLog,
            "[before] [Render thread/INFO]: Loaded 41 advancements\n",
        )
        val beforeRotation = snapshotLog(guestLog)
        Files.move(guestLog, guestLog.resolveSibling("latest.log.1"))
        Files.writeString(
            guestLog,
            "[startup] [Render thread/INFO]: Loaded 41 advancements\n",
        )
        assertTrue(hasNewLoadedAdvancements(guestLog, beforeRotation))
    }

    @Test
    fun `first poll after rotation keeps an already logged successful join`() {
        val guestLog = tempDir.resolve("latest.log")
        Files.writeString(
            guestLog,
            "[previous] [Render thread/INFO]: Loaded 41 advancements\n",
        )
        val beforeLaunch = snapshotLog(guestLog)

        Files.move(guestLog, guestLog.resolveSibling("latest.log.1"))
        Files.writeString(
            guestLog,
            "[join] [Render thread/INFO]: Loaded 41 advancements\n",
        )

        assertTrue(hasNewLoadedAdvancements(guestLog, beforeLaunch))
    }

    @Test
    fun `saved friend requests and joins a live singleplayer world`() =
        runBlocking {
            val dataValue = System.getenv("LIVE_DATA")
            val targetValue = System.getenv("LIVE_TARGET_FILE")
                ?: System.getenv("LIVE_PORT_FILE")
            val hostLogValue = System.getenv("LIVE_HOST_LOG")
            assumeTrue(
                dataValue != null && targetValue != null && hostLogValue != null,
                "LIVE_DATA, LIVE_TARGET_FILE, and LIVE_HOST_LOG enable this E2E",
            )
            val dataDirectory = Path.of(checkNotNull(dataValue))
            val targetFile = Path.of(checkNotNull(targetValue))
            val hostLog = Path.of(checkNotNull(hostLogValue))
            val guestLog = System.getenv("LIVE_GUEST_LOG")?.let(Path::of)
            val playerName = System.getenv("LIVE_PLAYER_NAME") ?: "bob"
            val forceConnectFallback =
                System.getenv("LIVE_FORCE_CONNECT_FALLBACK") == "true"
            val joinedLine = "] $playerName joined the game"
            val joinsBefore = Files.readString(hostLog)
                .lineSequence()
                .count { joinedLine in it }
            val guestLogBefore = guestLog?.let(::snapshotLog)
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
                val activity = activityResult.getOrNull()
                    ?: fail(
                        activityResult.leftOrNull()?.safeMessage
                            ?: "Host returned no friend activity",
                    )
                assertEquals(FriendActivityKind.HOSTING_WORLD, activity.kind)

                // A hidden world name intentionally skips raw Minecraft status.
                // Authenticated activity remains the privacy-safe authority,
                // and gameplay admission is independent.
                if (activity.description != null) {
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
                if (forceConnectFallback) {
                    forceDirectFailure(browser)
                }
                val gameplay = browser.join(
                    friend,
                    DirectP2pAuthMode.OFFLINE,
                ).getOrNull() ?: fail("No gameplay route was available")
                gameplay.use {
                    val target = when (gameplay) {
                        is GuestJoinTarget.Direct -> {
                            assertTrue(!forceConnectFallback)
                            gameplay.localAddress.port.toString()
                        }

                        is GuestJoinTarget.Connect -> {
                            assertTrue(forceConnectFallback)
                            gameplay.publicAddress
                        }
                    }
                    Files.writeString(targetFile, target)
                    withTimeout(180_000) {
                        while (Files.readString(hostLog)
                                .lineSequence()
                                .count { joinedLine in it } <= joinsBefore
                        ) {
                            delay(100)
                        }
                    }
                    if (guestLog != null && guestLogBefore != null) {
                        withTimeout(180_000) {
                            while (
                                !hasNewLoadedAdvancements(
                                    guestLog,
                                    guestLogBefore,
                                )
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

    private fun forceDirectFailure(browser: FabricShareBrowser) {
        val field = FabricShareBrowser::class.java
            .getDeclaredField("node")
            .apply { isAccessible = true }
        (field.get(browser) as AutoCloseable).close()
    }

    private fun snapshotLog(path: Path): LogSnapshot =
        readLog(path) ?: LogSnapshot(
            exists = false,
            fileKey = null,
            contents = "",
            loadedAdvancements = 0,
        )

    private fun hasNewLoadedAdvancements(
        path: Path,
        before: LogSnapshot,
    ): Boolean {
        val current = readLog(path) ?: return false
        val sameFile = before.exists &&
            if (before.fileKey != null && current.fileKey != null) {
                before.fileKey == current.fileKey
            } else {
                current.contents.startsWith(before.contents)
            }
        return if (sameFile && current.contents.startsWith(before.contents)) {
            current.loadedAdvancements > before.loadedAdvancements
        } else {
            current.loadedAdvancements > 0
        }
    }

    private fun readLog(path: Path): LogSnapshot? = try {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        val contents = Files.readString(path)
        LogSnapshot(
            exists = true,
            fileKey = attributes.fileKey(),
            contents = contents,
            loadedAdvancements = loadedAdvancementsCount(contents),
        )
    } catch (_: IOException) {
        null
    }

    private fun loadedAdvancementsCount(contents: String): Int =
        contents.lineSequence().count {
            "Loaded " in it && " advancements" in it
        }

    private data class LogSnapshot(
        val exists: Boolean,
        val fileKey: Any?,
        val contents: String,
        val loadedAdvancements: Int,
    )
}
