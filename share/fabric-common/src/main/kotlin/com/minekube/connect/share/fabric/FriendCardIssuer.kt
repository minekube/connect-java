package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.flatMap
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.direct.ShareInvitePayload
import com.minekube.connect.share.direct.SignedShareInvite
import com.minekube.connect.share.friend.ShareAccessIdentityStore
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendStoreError
import com.minekube.connect.share.friend.SavedFriend
import com.minekube.connect.tunnel.p2p.DirectP2pNode
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data object FriendCardIssueFailure

class FriendCardReceiver(
    private val store: FriendStore,
) {
    fun confirmPending(
        peerId: String,
    ): Either<FriendStoreError, SavedFriend> =
        store.confirmPending(peerId)

    fun receive(
        invitation: String,
        displayName: String,
        authenticatedMinecraftUuid: UUID?,
        now: Instant = Instant.now(),
    ): Either<FriendStoreError, SavedFriend> =
        store.accept(invitation, displayName, now).flatMap { friend ->
            store.updatePermissions(
                friend.peerId,
                friend.permissions.copy(
                    canJoinAutomatically = true,
                ),
            )
        }.flatMap { friend ->
            authenticatedMinecraftUuid?.let { minecraftUuid ->
                store.linkMinecraftIdentity(
                    friend.peerId,
                    minecraftUuid,
                )
            } ?: Either.Right(friend)
        }
}

class FriendCardIssuer(
    private val dataDirectory: Path,
    private val connectAddress: suspend () -> String?,
) {
    suspend fun issue(
        now: Instant = Instant.now(),
    ): Either<FriendCardIssueFailure, String> =
        Either.catch {
            val access = ShareAccessIdentityStore(
                dataDirectory,
            ).currentOrCreate()
            DirectP2pNode(
                dataDirectory.resolve(IDENTITY_FILE_NAME),
            ).use { node ->
                val payload = ShareInvitePayload(
                    wireVersion = ShareInviteCodec.WIRE_VERSION,
                    shareId = access.shareId,
                    expiresAtEpochMillis = now
                        .plusSeconds(CARD_LIFETIME_SECONDS)
                        .toEpochMilli(),
                    connectAddress = connectAddress(),
                    peerId = node.peerId(),
                    internetDirectEnabled = false,
                    directCandidates = emptyList(),
                    capability = access.capability,
                )
                val publicKey = node.publicKey()
                val unsigned = ShareInviteCodec.unsignedBytes(
                    payload,
                    publicKey,
                )
                ShareInviteCodec.encode(
                    SignedShareInvite(
                        payload = payload,
                        publicKey = publicKey,
                        signature = node.sign(unsigned),
                    ),
                )
            }
        }.mapLeft {
            FriendCardIssueFailure
        }

    private companion object {
        private const val IDENTITY_FILE_NAME =
            "share-libp2p-identity.key"
        private const val CARD_LIFETIME_SECONDS = 24 * 60 * 60L
    }
}
