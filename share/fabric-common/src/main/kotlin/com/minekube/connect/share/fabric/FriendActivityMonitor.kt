package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.fx.coroutines.parMap
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.SavedFriend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class FriendActivityMonitor private constructor(
    private val friends: () -> List<SavedFriend>,
    private val query: suspend (SavedFriend) ->
        Either<FriendRequestFailure, FriendActivity>,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        store: FriendStore,
        query: suspend (SavedFriend) ->
            Either<FriendRequestFailure, FriendActivity>,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(store::all, query, ioDispatcher)

    private val mutableState =
        MutableStateFlow<Map<String, FriendActivity>>(emptyMap())
    val state: StateFlow<Map<String, FriendActivity>> =
        mutableState.asStateFlow()

    suspend fun refresh() = withContext(ioDispatcher) {
        mutableState.value = runCatching(friends)
            .getOrDefault(emptyList())
            .take(MAX_QUERIED_FRIENDS)
            .parMap(
                context = ioDispatcher,
                concurrency = MAX_CONCURRENT_QUERIES,
            ) { friend ->
                query(friend).getOrNull()?.let { friend.peerId to it }
            }
            .filterNotNull()
            .toMap()
    }

    companion object {
        internal fun testing(
            friends: () -> List<SavedFriend>,
            query: suspend (SavedFriend) ->
                Either<FriendRequestFailure, FriendActivity>,
            ioDispatcher: CoroutineDispatcher,
        ) = FriendActivityMonitor(friends, query, ioDispatcher)

        private const val MAX_QUERIED_FRIENDS = 32
        private const val MAX_CONCURRENT_QUERIES = 4
    }
}
