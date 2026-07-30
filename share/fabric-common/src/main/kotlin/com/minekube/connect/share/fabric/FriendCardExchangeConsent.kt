package com.minekube.connect.share.fabric

class FriendCardExchangeConsent(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var armedAtMillis: Long? = null

    @Synchronized
    fun arm() {
        armedAtMillis = nowMillis()
    }

    @Synchronized
    fun consume(): Boolean {
        val armedAt = armedAtMillis ?: return false
        armedAtMillis = null
        return nowMillis() - armedAt <= CONSENT_LIFETIME_MILLIS
    }

    @Synchronized
    fun cancel() {
        armedAtMillis = null
    }

    companion object {
        const val CONSENT_LIFETIME_MILLIS = 120_000L

        fun shouldArm(
            savedFriendJoin: Boolean,
            canSeeMyWorlds: Boolean?,
        ): Boolean =
            savedFriendJoin && canSeeMyWorlds == true
    }
}
