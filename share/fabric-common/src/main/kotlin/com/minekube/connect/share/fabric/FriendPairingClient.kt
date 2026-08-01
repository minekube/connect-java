package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.FriendStoreError
import com.minekube.connect.share.friend.SavedFriend
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface FriendPairingFailure {
    val safeMessage: String

    data class Store(
        val error: FriendStoreError,
    ) : FriendPairingFailure {
        override val safeMessage: String = error.safeMessage
    }

    data object CardIssue : FriendPairingFailure {
        override val safeMessage =
            "Your Connect Share friend card could not be created"
    }

    data class Route(
        val error: GuestJoinFailure,
    ) : FriendPairingFailure {
        override val safeMessage: String = error.safeMessage
    }

    data class Delivery(
        val error: FriendRequestFailure,
    ) : FriendPairingFailure {
        override val safeMessage: String = error.safeMessage
    }
}

class FriendPairingClient(
    private val store: FriendStore,
    private val issuer: FriendCardIssuer,
    private val receiver: FriendCardReceiver,
    private val requestClient: FriendRequestClient,
    private val now: () -> Instant = Instant::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun send(
        invitation: String,
        friendDisplayName: String,
        senderDisplayName: String,
        route: suspend (SavedFriend) ->
            Either<GuestJoinFailure, GuestJoinTarget>,
        onReceived: () -> Unit,
    ): Either<FriendPairingFailure, SavedFriend> =
        withContext(ioDispatcher) {
            either {
                val pending = store.sendRequest(
                    invitationUri = invitation,
                    displayName = friendDisplayName,
                    now = now(),
                ).mapLeft(FriendPairingFailure::Store).bind()
                val senderCard = issuer.issue(now())
                    .mapLeft { FriendPairingFailure.CardIssue }
                    .bind()
                val target = route(pending)
                    .mapLeft(FriendPairingFailure::Route)
                    .bind()
                ensure(target is GuestJoinTarget.Direct) {
                    target.close()
                    FriendPairingFailure.Route(GuestJoinFailure.NoRoute)
                }
                val hostCard = requestClient.exchange(
                    target = target,
                    request = FriendControlRequest(
                        requestId = UUID.randomUUID(),
                        relationshipId = pending.relationshipId,
                        displayName = senderDisplayName,
                        invitation = senderCard,
                    ),
                    onReceived = onReceived,
                ).mapLeft(FriendPairingFailure::Delivery).bind()
                receiver.receive(
                    invitation = hostCard,
                    displayName = friendDisplayName,
                    authenticatedMinecraftUuid = null,
                    now = now(),
                ).mapLeft(FriendPairingFailure::Store).bind()
            }
        }
}
