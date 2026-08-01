package com.minekube.connect.share.friend

import arrow.core.Either
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.SignedShareInvite
import java.nio.file.Files
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
    fun `loaded relationships are served from memory instead of rereading each tick`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        val loaded = store.all()
        Files.writeString(
            tempDir.resolve(FriendStore.FILE_NAME),
            "{broken-json",
        )

        assertEquals(loaded, store.all())
        assertEquals(loaded, store.all())
        assertTrue(
            runCatching { FriendStore(tempDir).all() }.isFailure,
        )
    }

    @Test
    fun `sending a signed link stores only an outgoing request`() {
        val store = FriendStore(tempDir)

        val request = assertIs<Either.Right<SavedFriend>>(
            store.sendRequest(signedLink(), "Robin", NOW),
        ).value

        assertEquals(
            FriendRelationshipStatus.PENDING_OUTGOING,
            request.relationshipStatus,
        )
        assertTrue(store.all().isEmpty())
        assertEquals(
            listOf(request),
            FriendStore(tempDir).outgoingRequests(),
        )
    }

    @Test
    fun `direct internet social candidates survive restart`() {
        val store = FriendStore(tempDir)
        val saved = store.accept(
            signedLink(
                internetDirectEnabled = true,
                directCandidates = listOf(INTERNET_ADDRESS),
            ),
            "Robin",
            NOW,
        ).getOrNull()!!

        assertTrue(saved.internetDirectEnabled)
        assertEquals(listOf(INTERNET_ADDRESS), saved.directCandidates)
        assertEquals(saved, FriendStore(tempDir).all().single())
    }

    @Test
    fun `confirming an outgoing request promotes it across restarts`() {
        val store = FriendStore(tempDir)
        store.sendRequest(signedLink(), "Robin", NOW)

        val confirmed = assertIs<Either.Right<SavedFriend>>(
            store.confirmOutgoing(PEER_ID),
        ).value

        assertEquals(
            FriendRelationshipStatus.CONFIRMED,
            confirmed.relationshipStatus,
        )
        assertEquals(
            listOf(confirmed),
            FriendStore(tempDir).all(),
        )
        assertTrue(FriendStore(tempDir).outgoingRequests().isEmpty())
    }

    @Test
    fun `sending the same link never demotes a confirmed friend`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)

        val received = assertIs<Either.Right<SavedFriend>>(
            store.sendRequest(signedLink(), "Robin", NOW),
        ).value

        assertEquals(
            FriendRelationshipStatus.CONFIRMED,
            received.relationshipStatus,
        )
        assertEquals(PEER_ID, store.all().single().peerId)
        assertTrue(store.outgoingRequests().isEmpty())
    }

    @Test
    fun `legacy unverified relationships migrate to outgoing`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        stripRelationshipStatus()

        val migrated = FriendStore(tempDir)

        assertTrue(migrated.all().isEmpty())
        assertEquals(PEER_ID, migrated.outgoingRequests().single().peerId)
    }

    @Test
    fun `broken incoming status migrates to outgoing`() {
        val store = FriendStore(tempDir)
        store.sendRequest(signedLink(), "Robin", NOW)
        replaceRelationshipStatus(
            from = "PENDING_OUTGOING",
            to = "PENDING_INCOMING",
        )

        val migrated = FriendStore(tempDir)

        assertTrue(migrated.all().isEmpty())
        assertEquals(PEER_ID, migrated.outgoingRequests().single().peerId)
    }

    @Test
    fun `legacy automatically trusted relationships remain confirmed`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        store.updatePermissions(
            PEER_ID,
            FriendPermissions(canJoinAutomatically = true),
        )
        stripRelationshipStatus()

        val migrated = FriendStore(tempDir)

        assertEquals(PEER_ID, migrated.all().single().peerId)
        assertTrue(migrated.outgoingRequests().isEmpty())
    }

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
        assertEquals(
            FriendRelationshipStatus.CONFIRMED,
            accepted.relationshipStatus,
        )
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
    fun `never allow is durable and distinct from ask every time`() {
        val store = FriendStore(tempDir)
        val friend = store.accept(signedLink(), "Robin", NOW).getOrNull()!!

        store.updatePermissions(
            friend.peerId,
            friend.permissions.copy(
                accessPolicy = FriendAccessPolicy.NEVER_ALLOW,
            ),
        )

        val reloaded = FriendStore(tempDir).all().single()
        assertEquals(
            FriendAccessPolicy.NEVER_ALLOW,
            reloaded.permissions.accessPolicy,
        )
        assertFalse(reloaded.permissions.canJoinAutomatically)
    }

    @Test
    fun `blocking revokes friendship and rejects the same identity until unblocked`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)

        assertTrue(store.block(PEER_ID, NOW))

        val reloaded = FriendStore(tempDir)
        assertTrue(reloaded.all().isEmpty())
        assertEquals(PEER_ID, reloaded.blocked().single().peerId)
        assertEquals(PEER_ID, reloaded.pendingRemovals().single().friend.peerId)
        assertIs<Either.Left<FriendStoreError.Blocked>>(
            reloaded.accept(signedLink(), "Robin", NOW.plusSeconds(1)),
        )

        assertTrue(reloaded.unblock(PEER_ID))
        assertTrue(
            reloaded.sendRequest(
                signedLink(),
                "Robin",
                NOW.plusSeconds(2),
            ).isRight(),
        )
    }

    @Test
    fun `approved friend can be bound to an authenticated Minecraft identity`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        val minecraftUuid = UUID.fromString(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        )

        assertIs<Either.Right<SavedFriend>>(
            store.linkMinecraftIdentity(PEER_ID, minecraftUuid),
        )

        assertEquals(
            minecraftUuid,
            FriendStore(tempDir).all().single().minecraftUuid,
        )
    }

    @Test
    fun `removing a friend revokes the locally stored relationship`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)

        val removed = store.remove(PEER_ID, NOW)

        assertTrue(removed)
        assertTrue(FriendStore(tempDir).all().isEmpty())
        val pending = FriendStore(tempDir).pendingRemovals().single()
        assertEquals(PEER_ID, pending.friend.peerId)
        assertEquals(NOW, pending.removedAt)
        assertFalse(store.remove(PEER_ID))
    }

    @Test
    fun `acknowledging a removal clears its durable tombstone`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        store.remove(PEER_ID, NOW)
        val operation = store.pendingRemovals().single()

        assertTrue(store.acknowledgeRemoval(operation.operationId))

        assertTrue(FriendStore(tempDir).pendingRemovals().isEmpty())
        assertFalse(store.acknowledgeRemoval(operation.operationId))
    }

    @Test
    fun `remote removal is idempotent and does not create a reply tombstone`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)

        assertTrue(store.applyRemoteRemoval(PEER_ID))
        assertFalse(store.applyRemoteRemoval(PEER_ID))

        val reloaded = FriendStore(tempDir)
        assertTrue(reloaded.all().isEmpty())
        assertTrue(reloaded.pendingRemovals().isEmpty())
    }

    @Test
    fun `explicitly adding a removed friend cancels the stale removal`() {
        val store = FriendStore(tempDir)
        store.accept(signedLink(), "Robin", NOW)
        store.remove(PEER_ID, NOW)

        store.sendRequest(signedLink(), "Robin", NOW.plusSeconds(1))

        val reloaded = FriendStore(tempDir)
        assertEquals(PEER_ID, reloaded.outgoingRequests().single().peerId)
        assertTrue(reloaded.pendingRemovals().isEmpty())
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
        internetDirectEnabled: Boolean = false,
        directCandidates: List<String> = emptyList(),
    ): String {
        val payload = ShareInvitePayload(
            wireVersion = ShareInviteCodec.WIRE_VERSION,
            shareId = SHARE_ID,
            expiresAtEpochMillis = expiresAt.toEpochMilli(),
            connectAddress = CONNECT_ADDRESS,
            peerId = PEER_ID,
            internetDirectEnabled = internetDirectEnabled,
            directCandidates = directCandidates,
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

    private fun stripRelationshipStatus() {
        val file = tempDir.resolve(FriendStore.FILE_NAME)
        val withoutStatus = Files.readString(file).replace(
            Regex(
                ""","relationshipStatus":"[A-Z_]+"""",
            ),
            "",
        )
        Files.writeString(file, withoutStatus)
    }

    private fun replaceRelationshipStatus(
        from: String,
        to: String,
    ) {
        val file = tempDir.resolve(FriendStore.FILE_NAME)
        Files.writeString(
            file,
            Files.readString(file).replace(from, to),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z")
        val SHARE_ID: UUID =
            UUID.fromString("9e511188-31a9-43ac-9107-29d94410d554")
        const val PEER_ID = "12D3KooWStableFriendPeer"
        const val CONNECT_ADDRESS = "purple-del.play.minekube.net"
        const val CAPABILITY = "friend-capability-123456789"
        const val INTERNET_ADDRESS =
            "/ip6/2001:db8::20/tcp/4001/p2p/$PEER_ID"
        val KEY_PAIR: KeyPair =
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    }
}
