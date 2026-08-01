package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionIdentity
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ApprovedJoinProof(
    val authenticatedMinecraftUuid: UUID?,
)

class ApprovedJoinTracker(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val approved =
        ConcurrentHashMap<PlayerKey, TimedProof>()

    fun record(
        identity: AdmissionIdentity,
        answer: AdmissionAnswer,
    ) {
        if (answer != AdmissionAnswer.ALLOW) {
            return
        }
        val now = nowMillis()
        approved.entries.removeIf {
            now - it.value.approvedAtMillis > PROOF_LIFETIME_MILLIS
        }
        approved[
            PlayerKey(identity.name.normalized(), identity.uuid),
        ] = TimedProof(
            proof = ApprovedJoinProof(
                authenticatedMinecraftUuid =
                    (identity as? AdmissionIdentity.Authenticated)?.uuid,
            ),
            directPeerId = identity.directPeerId,
            approvedAtMillis = now,
        )
    }

    fun hasProof(
        name: String,
        uuid: UUID,
    ): Boolean {
        val key = PlayerKey(name.normalized(), uuid)
        val timedProof = approved[key] ?: return false
        if (
            nowMillis() - timedProof.approvedAtMillis >
            PROOF_LIFETIME_MILLIS
        ) {
            approved.remove(key, timedProof)
            return false
        }
        return true
    }

    fun consume(
        name: String,
        uuid: UUID,
    ): ApprovedJoinProof? {
        val timedProof = approved.remove(
            PlayerKey(name.normalized(), uuid),
        ) ?: return null
        return timedProof.proof.takeIf {
            nowMillis() - timedProof.approvedAtMillis <=
                PROOF_LIFETIME_MILLIS
        }
    }

    fun revokeDirectPeer(
        peerId: String,
        minecraftUuid: UUID? = null,
    ): Int {
        val matches = approved.entries.filter {
            it.value.directPeerId == peerId ||
                (
                    it.value.directPeerId == null &&
                        minecraftUuid != null &&
                        it.key.uuid == minecraftUuid
                    )
        }
        matches.forEach { approved.remove(it.key, it.value) }
        return matches.size
    }

    private fun String.normalized(): String =
        lowercase(Locale.ROOT)

    private data class PlayerKey(
        val name: String,
        val uuid: UUID,
    )

    private data class TimedProof(
        val proof: ApprovedJoinProof,
        val directPeerId: String?,
        val approvedAtMillis: Long,
    )

    private companion object {
        const val PROOF_LIFETIME_MILLIS = 120_000L
    }
}
