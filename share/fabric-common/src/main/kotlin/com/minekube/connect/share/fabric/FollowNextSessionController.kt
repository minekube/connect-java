package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FollowIntent(
    val peerId: String,
    val displayName: String,
    val expiresAt: Instant,
    val emittedEpoch: String? = null,
)

sealed interface FollowAction {
    val peerId: String
    val displayName: String

    data class RequestJoin(
        override val peerId: String,
        override val displayName: String,
        val sessionEpoch: String,
    ) : FollowAction

    data class OfferJoinNow(
        override val peerId: String,
        override val displayName: String,
        val sessionEpoch: String,
    ) : FollowAction

    data class Expired(
        override val peerId: String,
        override val displayName: String,
    ) : FollowAction

    data class Cancelled(
        override val peerId: String,
        override val displayName: String,
    ) : FollowAction
}

class FollowNextSessionController(
    private val now: () -> Instant = Instant::now,
    private val lifetimeSeconds: Long = DEFAULT_LIFETIME_SECONDS,
) {
    private val mutableState = MutableStateFlow<Map<String, FollowIntent>>(
        emptyMap(),
    )
    val state: StateFlow<Map<String, FollowIntent>> = mutableState.asStateFlow()

    @Synchronized
    fun follow(peerId: String, displayName: String) {
        val normalizedName = displayName.trim().ifEmpty { "Friend" }
        mutableState.value = mutableState.value + (
            peerId to FollowIntent(
                peerId = peerId,
                displayName = normalizedName,
                expiresAt = now().plusSeconds(lifetimeSeconds),
            )
            )
    }

    @Synchronized
    fun cancel(peerId: String): Boolean {
        if (peerId !in mutableState.value) return false
        mutableState.value = mutableState.value - peerId
        return true
    }

    @Synchronized
    fun complete(peerId: String): Boolean = cancel(peerId)

    @Synchronized
    fun update(
        activities: Map<String, FriendActivity>,
        activeGameplay: Boolean,
        confirmedPeerIds: Set<String>,
    ): List<FollowAction> {
        val instant = now()
        val actions = mutableListOf<FollowAction>()
        val retained = linkedMapOf<String, FollowIntent>()
        mutableState.value.values.forEach { intent ->
            when {
                intent.peerId !in confirmedPeerIds ->
                    actions += FollowAction.Cancelled(
                        intent.peerId,
                        intent.displayName,
                    )

                !instant.isBefore(intent.expiresAt) ->
                    actions += FollowAction.Expired(
                        intent.peerId,
                        intent.displayName,
                    )

                else -> {
                    val activity = activities[intent.peerId]
                    val epoch = activity?.takeIf {
                        it.joinable && it.kind != FriendActivityKind.ONLINE
                    }?.effectiveEpoch()
                    if (epoch != null && epoch != intent.emittedEpoch) {
                        actions += if (activeGameplay) {
                            FollowAction.OfferJoinNow(
                                intent.peerId,
                                intent.displayName,
                                epoch,
                            )
                        } else {
                            FollowAction.RequestJoin(
                                intent.peerId,
                                intent.displayName,
                                epoch,
                            )
                        }
                        retained[intent.peerId] = intent.copy(
                            emittedEpoch = epoch,
                        )
                    } else {
                        retained[intent.peerId] = intent
                    }
                }
            }
        }
        mutableState.value = retained
        return actions
    }

    private fun FriendActivity.effectiveEpoch(): String =
        sessionEpoch ?: listOf(kind.name, description.orEmpty(), joinable)
            .joinToString(":")

    private companion object {
        const val DEFAULT_LIFETIME_SECONDS = 30 * 60L
    }
}
