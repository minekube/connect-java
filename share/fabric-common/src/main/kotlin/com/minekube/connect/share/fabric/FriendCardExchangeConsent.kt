package com.minekube.connect.share.fabric

data class FriendCardExchangeProof(
    val peerId: String,
)

class FriendCardExchangeConsent(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var armed: TimedExchange? = null

    @Synchronized
    fun arm(peerId: String) {
        require(peerId.isNotBlank())
        armed = TimedExchange(
            proof = FriendCardExchangeProof(peerId),
            armedAtMillis = nowMillis(),
        )
    }

    @Synchronized
    fun consume(): FriendCardExchangeProof? {
        val exchange = armed ?: return null
        armed = null
        return exchange.proof.takeIf {
            nowMillis() - exchange.armedAtMillis <=
                CONSENT_LIFETIME_MILLIS
        }
    }

    @Synchronized
    fun cancel() {
        armed = null
    }

    private data class TimedExchange(
        val proof: FriendCardExchangeProof,
        val armedAtMillis: Long,
    )

    companion object {
        const val CONSENT_LIFETIME_MILLIS = 120_000L

        fun shouldArm(
            savedFriendJoin: Boolean,
            canSeeMyWorlds: Boolean?,
        ): Boolean =
            savedFriendJoin && canSeeMyWorlds == true
    }
}
