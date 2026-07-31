package com.minekube.connect.share.fabric

import arrow.fx.coroutines.parMap
import com.minekube.connect.share.direct.ShareRoute
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.SavedFriend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class RemoteFriendPresence(
    val peerId: String,
    val displayName: String,
    val online: Boolean,
    val description: String? = null,
    val notifyWhenOnline: Boolean,
    val route: ShareRoute? = null,
)

class FriendOnlineTracker {
    private var onlinePeerIds: Set<String> = emptySet()

    fun update(
        presence: Map<String, RemoteFriendPresence>,
    ): List<RemoteFriendPresence> {
        val currentlyOnline = presence.values
            .filter(RemoteFriendPresence::online)
        val notifications = currentlyOnline.filter {
            it.notifyWhenOnline && it.peerId !in onlinePeerIds
        }
        onlinePeerIds = currentlyOnline.mapTo(mutableSetOf()) {
            it.peerId
        }
        return notifications
    }
}

class FriendPresenceMonitor private constructor(
    private val friends: () -> List<SavedFriend>,
    private val directProbe: suspend (SavedFriend) -> ServerPresence?,
    private val ioDispatcher: CoroutineDispatcher,
) {
    constructor(
        store: FriendStore,
        directProbe: suspend (SavedFriend) -> ServerPresence? = { null },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        friends = store::all,
        directProbe = directProbe,
        ioDispatcher = ioDispatcher,
    )

    private val mutableState =
        MutableStateFlow<Map<String, RemoteFriendPresence>>(emptyMap())

    val state: StateFlow<Map<String, RemoteFriendPresence>> =
        mutableState.asStateFlow()

    suspend fun refresh() = withContext(ioDispatcher) {
        val saved = runCatching(friends)
            .getOrDefault(emptyList())
            .take(MAX_PROBED_FRIENDS)
        val results = saved.parMap(
            context = ioDispatcher,
            concurrency = MAX_CONCURRENT_PROBES,
        ) { friend ->
            val directPresence = try {
                directProbe(friend)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            friend.peerId to RemoteFriendPresence(
                peerId = friend.peerId,
                displayName = friend.displayName,
                online = directPresence != null,
                description = directPresence?.description,
                notifyWhenOnline =
                    friend.permissions.notifyWhenOnline,
                route = directPresence?.let { ShareRoute.DIRECT_LAN },
            )
        }
        mutableState.value = results.toMap()
    }

    companion object {
        internal fun testing(
            friends: () -> List<SavedFriend>,
            directProbe: suspend (SavedFriend) -> ServerPresence? = {
                null
            },
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ) = FriendPresenceMonitor(
            friends,
            directProbe,
            ioDispatcher,
        )

        private const val MAX_PROBED_FRIENDS = 32
        private const val MAX_CONCURRENT_PROBES = 4
    }
}
