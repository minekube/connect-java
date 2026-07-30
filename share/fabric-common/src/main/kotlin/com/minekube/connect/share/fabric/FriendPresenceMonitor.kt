package com.minekube.connect.share.fabric

import arrow.fx.coroutines.parMap
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.SavedFriend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteFriendPresence(
    val peerId: String,
    val displayName: String,
    val online: Boolean,
    val description: String? = null,
    val notifyWhenOnline: Boolean,
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
    private val probe: FriendStatusProbe,
) {
    constructor(
        store: FriendStore,
        probe: FriendStatusProbe = MinecraftStatusProbe(),
    ) : this(
        friends = store::all,
        probe = probe,
    )

    private val mutableState =
        MutableStateFlow<Map<String, RemoteFriendPresence>>(emptyMap())

    val state: StateFlow<Map<String, RemoteFriendPresence>> =
        mutableState.asStateFlow()

    suspend fun refresh() {
        val saved = runCatching(friends)
            .getOrDefault(emptyList())
            .take(MAX_PROBED_FRIENDS)
        val results = saved.parMap(
            context = Dispatchers.IO,
            concurrency = MAX_CONCURRENT_PROBES,
        ) { friend ->
            val result = friend.connectAddress?.let {
                probe.probe(it)
            }
            val presence = result?.getOrNull()
            friend.peerId to RemoteFriendPresence(
                peerId = friend.peerId,
                displayName = friend.displayName,
                online = presence != null,
                description = presence?.description,
                notifyWhenOnline =
                    friend.permissions.notifyWhenOnline,
            )
        }
        mutableState.value = results.toMap()
    }

    companion object {
        internal fun testing(
            friends: () -> List<SavedFriend>,
            probe: FriendStatusProbe,
        ) = FriendPresenceMonitor(friends, probe)

        private const val MAX_PROBED_FRIENDS = 32
        private const val MAX_CONCURRENT_PROBES = 4
    }
}
