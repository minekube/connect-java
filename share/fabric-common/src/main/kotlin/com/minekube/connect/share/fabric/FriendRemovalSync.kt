package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.fx.coroutines.parMap
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.PendingFriendRemoval

data class RemovalSyncSummary(
    val delivered: Int,
    val pending: Int,
)

fun interface FriendRemovalDelivery {
    suspend fun deliver(
        removal: PendingFriendRemoval,
    ): Either<FriendRequestFailure, Unit>
}

class FriendRemovalSync(
    private val store: FriendStore,
    private val delivery: FriendRemovalDelivery,
) {
    suspend fun sync(): RemovalSyncSummary {
        val pending = store.pendingRemovals()
        val delivered = pending.parMap(concurrency = MAX_CONCURRENT_DELIVERIES) {
            removal ->
            delivery.deliver(removal).fold(
                ifLeft = { false },
                ifRight = {
                    store.acknowledgeRemoval(removal.operationId)
                },
            )
        }.count { it }
        return RemovalSyncSummary(
            delivered = delivered,
            pending = store.pendingRemovals().size,
        )
    }

    private companion object {
        const val MAX_CONCURRENT_DELIVERIES = 4
    }
}
