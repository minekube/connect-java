package com.minekube.connect.share.fabric

import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.share.friend.FriendStore
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class FriendRemovalSyncTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `failed removal stays durable and a later sync acknowledges it`() = runTest {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        store.remove(PEER_ID, NOW)
        var reachable = false
        var attempts = 0
        val sync = FriendRemovalSync(store) {
            attempts++
            if (reachable) Unit.right() else FriendRequestFailure.Unreachable.left()
        }

        assertEquals(RemovalSyncSummary(delivered = 0, pending = 1), sync.sync())
        assertEquals(1, FriendStore(tempDir).pendingRemovals().size)

        reachable = true
        assertEquals(RemovalSyncSummary(delivered = 1, pending = 0), sync.sync())
        assertTrue(FriendStore(tempDir).pendingRemovals().isEmpty())
        assertEquals(2, attempts)
    }

    private fun signedLink(): String {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = ShareInvitePayload(
            wireVersion = ShareInviteCodec.WIRE_VERSION,
            shareId = UUID.randomUUID(),
            expiresAtEpochMillis = NOW.plusSeconds(3_600).toEpochMilli(),
            connectAddress = "purple-del.play.minekube.net",
            peerId = PEER_ID,
            internetDirectEnabled = false,
            directCandidates = emptyList(),
            capability = "friend-capability-123456789",
        )
        val unsigned = ShareInviteCodec.unsignedBytes(payload, pair.public.encoded)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(unsigned)
            sign()
        }
        return ShareInviteCodec.encode(
            SignedShareInvite(payload, pair.public.encoded, signature),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        const val PEER_ID = "12D3KooWStableFriendPeer"
    }
}
