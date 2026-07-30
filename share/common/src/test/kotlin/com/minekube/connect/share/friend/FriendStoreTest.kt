package com.minekube.connect.share.friend

import arrow.core.Either
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.SignedShareInvite
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FriendStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `accepting one signed link saves a friend across restarts`() {
        val link = signedLink()
        val store = FriendStore(tempDir)

        val accepted = assertIs<Either.Right<SavedFriend>>(
            store.accept(link, "Robin", NOW),
        ).value
        val reloaded = FriendStore(tempDir).all()

        assertEquals(listOf(accepted), reloaded)
        assertEquals(PEER_ID, accepted.peerId)
        assertEquals(SHARE_ID, accepted.shareId)
        assertEquals(CONNECT_ADDRESS, accepted.connectAddress)
        assertTrue(accepted.permissions.notifyWhenOnline)
        assertTrue(accepted.permissions.canSeeMyWorlds)
        assertFalse(accepted.permissions.canJoinAutomatically)
        assertFalse(accepted.toString().contains(CAPABILITY))
        assertContains(accepted.toString(), "capability=<redacted>")
    }

    @Test
    fun `friend settings can be managed without exchanging another link`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)

        assertIs<Either.Right<SavedFriend>>(
            store.rename(PEER_ID, "Robin from Discord"),
        )
        assertIs<Either.Right<SavedFriend>>(
            store.updatePermissions(
                PEER_ID,
                FriendPermissions(
                    notifyWhenOnline = false,
                    canSeeMyWorlds = true,
                    canJoinAutomatically = true,
                ),
            ),
        )

        val managed = FriendStore(tempDir).all().single()
        assertEquals("Robin from Discord", managed.displayName)
        assertFalse(managed.permissions.notifyWhenOnline)
        assertTrue(managed.permissions.canSeeMyWorlds)
        assertTrue(managed.permissions.canJoinAutomatically)
    }

    @Test
    fun `removing a friend revokes the locally stored relationship`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)

        val removed = store.remove(PEER_ID)

        assertTrue(removed)
        assertTrue(FriendStore(tempDir).all().isEmpty())
        assertFalse(store.remove(PEER_ID))
    }

    @Test
    fun `invalid or expired links are rejected without changing friends`() {
        val store = FriendStore(tempDir)

        val malformed = store.accept("minekube://share/not-valid", "Robin", NOW)
        val expired = store.accept(
            signedLink(expiresAt = NOW.minusSeconds(1)),
            "Robin",
            NOW,
        )

        assertIs<Either.Left<FriendStoreError.InvalidInvitation>>(malformed)
        assertIs<Either.Left<FriendStoreError.InvalidInvitation>>(expired)
        assertTrue(store.all().isEmpty())
    }

    private fun signedLink(
        expiresAt: Instant = NOW.plusSeconds(3_600),
    ): String {
        val payload = ShareInvitePayload(
            wireVersion = ShareInviteCodec.WIRE_VERSION,
            shareId = SHARE_ID,
            expiresAtEpochMillis = expiresAt.toEpochMilli(),
            connectAddress = CONNECT_ADDRESS,
            peerId = PEER_ID,
            internetDirectEnabled = false,
            directCandidates = emptyList(),
            capability = CAPABILITY,
        )
        val unsigned = ShareInviteCodec.unsignedBytes(
            payload,
            KEY_PAIR.public.encoded,
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(KEY_PAIR.private)
            update(unsigned)
            sign()
        }
        return ShareInviteCodec.encode(
            SignedShareInvite(
                payload = payload,
                publicKey = KEY_PAIR.public.encoded,
                signature = signature,
            ),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        val SHARE_ID: UUID =
            UUID.fromString("9e511188-31a9-43ac-9107-29d94410d554")
        const val PEER_ID = "12D3KooWStableFriendPeer"
        const val CONNECT_ADDRESS = "purple-del.play.minekube.net"
        const val CAPABILITY = "friend-capability-123456789"
        val KEY_PAIR: KeyPair =
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }
}
