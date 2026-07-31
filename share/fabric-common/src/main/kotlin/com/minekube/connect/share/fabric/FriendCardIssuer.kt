package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import arrow.core.raise.ensure
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
    fun confirmOutgoing(
        peerId: String,
    ): Either<FriendStoreError, SavedFriend> =
        store.confirmOutgoing(peerId)

    fun receive(
        invitation: String,
        displayName: String,
        authenticatedMinecraftUuid: UUID?,
        allowAutomaticJoin: Boolean = false,
        now: Instant = Instant.now(),
    ): Either<FriendStoreError, SavedFriend> =
        (if (allowAutomaticJoin) {
            store.acceptAndAllowJoin(invitation, displayName, now)
        } else {
            store.accept(invitation, displayName, now)
        }).flatMap { friend ->
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
    private val displayName: () -> String? = { null },
    private val accessIdentityStore: ShareAccessIdentityStore =
        ShareAccessIdentityStore(dataDirectory),
    private val connectAddress: suspend () -> String?,
) {
    suspend fun issue(
        now: Instant = Instant.now(),
    ): Either<FriendCardIssueFailure, String> = either {
        val normalizedDisplayName = displayName()?.trim()
        ensure(
            normalizedDisplayName == null ||
                normalizedDisplayName.length in 1..MAX_DISPLAY_NAME_LENGTH,
        ) {
            FriendCardIssueFailure
        }
        Either.catch {
            val access = accessIdentityStore.currentOrCreate()
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
                    displayName = normalizedDisplayName,
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
        }.bind()
    }

    private companion object {
        private const val IDENTITY_FILE_NAME =
            "share-libp2p-identity.key"
        private const val CARD_LIFETIME_SECONDS = 24 * 60 * 60L
        private const val MAX_DISPLAY_NAME_LENGTH = 64
    }
}
