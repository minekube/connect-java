package com.minekube.connect.share.admission

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdmissionController(
    private val scope: CoroutineScope,
    private val timeout: Duration = 30.seconds,
    private val maxPending: Int = 16,
    private val connectedCount: () -> Int,
    private val maxGuests: () -> Int,
    private val autoApprove: (AdmissionIdentity) -> Boolean = { false },
) {
    private val lock = Any()
    private val requests = linkedMapOf<AdmissionKey, PendingRequest>()
    private val authenticatedApprovals = mutableSetOf<AuthenticatedApproval>()
    private val preapprovedJoins = mutableSetOf<PreapprovedJoin>()
    private val mutablePending = MutableStateFlow<List<PendingAdmission>>(emptyList())

    val pending: StateFlow<List<PendingAdmission>> = mutablePending.asStateFlow()

    init {
        require(timeout.isPositive()) { "Admission timeout must be positive" }
        require(maxPending > 0) { "Maximum pending admissions must be positive" }
    }

    suspend fun request(
        identity: AdmissionIdentity,
        purpose: AdmissionPurpose = AdmissionPurpose.JOIN,
    ): AdmissionAnswer {
        val lookup = synchronized(lock) {
            val key = identity.admissionKey(purpose)
            requests[key]?.let {
                it.waiters++
                return@synchronized RequestLookup.Await(it, startTimeout = false)
            }
            if (
                purpose == AdmissionPurpose.JOIN &&
                connectedCount() >= maxGuests()
            ) {
                return@synchronized RequestLookup.Immediate(AdmissionAnswer.CAPACITY)
            }
            if (purpose == AdmissionPurpose.JOIN) {
                val preapproved = preapprovedJoins.firstOrNull {
                    it.matches(identity)
                }
                if (preapproved != null) {
                    preapprovedJoins.remove(preapproved)
                    return@synchronized RequestLookup.Immediate(
                        AdmissionAnswer.ALLOW,
                    )
                }
            }
            if (
                purpose == AdmissionPurpose.JOIN &&
                autoApprove(identity)
            ) {
                return@synchronized RequestLookup.Immediate(AdmissionAnswer.ALLOW)
            }
            if (
                purpose == AdmissionPurpose.JOIN &&
                identity is AdmissionIdentity.Authenticated &&
                authenticatedApprovals.any { it.matches(identity) }
            ) {
                return@synchronized RequestLookup.Immediate(AdmissionAnswer.ALLOW)
            }
            if (requests.size >= maxPending) {
                return@synchronized RequestLookup.Immediate(AdmissionAnswer.CAPACITY)
            }

            val request = PendingRequest(
                key = key,
                pending = PendingAdmission(
                    requestId = UUID.randomUUID(),
                    identity = identity,
                    purpose = purpose,
                ),
            )
            requests[key] = request
            publishPending()
            RequestLookup.Await(request, startTimeout = true)
        }

        return when (lookup) {
            is RequestLookup.Immediate -> lookup.answer
            is RequestLookup.Await -> {
                if (lookup.startTimeout) {
                    startTimeout(lookup.request)
                }
                try {
                    lookup.request.answer.await()
                } finally {
                    releaseWaiter(lookup.request)
                }
            }
        }
    }

    fun answer(requestId: UUID, allow: Boolean) {
        val answer = if (allow) AdmissionAnswer.ALLOW else AdmissionAnswer.DENY
        val completed = synchronized(lock) {
            val entry = requests.entries.firstOrNull {
                it.value.pending.requestId == requestId
            } ?: return
            requests.remove(entry.key)
            if (
                allow &&
                entry.value.pending.purpose == AdmissionPurpose.JOIN
            ) {
                val identity = entry.value.pending.identity
                if (identity is AdmissionIdentity.Authenticated) {
                    authenticatedApprovals += AuthenticatedApproval(
                        uuid = identity.uuid,
                        directPeerId = identity.directPeerId,
                        ingress = identity.ingress,
                    )
                }
            }
            publishPending()
            entry.value
        }
        complete(completed, answer)
    }

    fun denyDirectPeer(
        peerId: String,
        purpose: AdmissionPurpose,
    ): Int {
        val denied = synchronized(lock) {
            preapprovedJoins.removeIf { it.directPeerId == peerId }
            val matches = requests.entries.filter { entry ->
                entry.value.pending.purpose == purpose &&
                    entry.value.pending.identity.directPeerId == peerId
            }
            matches.forEach { requests.remove(it.key) }
            if (matches.isNotEmpty()) publishPending()
            matches.map { it.value }
        }
        denied.forEach { complete(it, AdmissionAnswer.DENY) }
        return denied.size
    }

    fun revokeDirectPeer(
        peerId: String,
        minecraftUuid: UUID? = null,
    ): Int {
        val revoked = synchronized(lock) {
            preapprovedJoins.removeIf { it.directPeerId == peerId }
            authenticatedApprovals.removeIf {
                it.directPeerId == peerId ||
                    (
                        it.directPeerId == null &&
                            minecraftUuid != null &&
                            it.uuid == minecraftUuid
                        )
            }
            val matches = requests.entries.filter { entry ->
                entry.value.pending.identity.directPeerId == peerId
            }
            matches.forEach { requests.remove(it.key) }
            if (matches.isNotEmpty()) publishPending()
            matches.map { it.value }
        }
        revoked.forEach { complete(it, AdmissionAnswer.DENY) }
        return revoked.size
    }

    fun resetShare() {
        val stopped = synchronized(lock) {
            val current = requests.values.toList()
            requests.clear()
            authenticatedApprovals.clear()
            preapprovedJoins.clear()
            publishPending()
            current
        }
        stopped.forEach {
            complete(it, AdmissionAnswer.STOPPED)
        }
    }

    fun approveNextJoin(identity: AdmissionIdentity) {
        synchronized(lock) {
            preapprovedJoins += PreapprovedJoin(
                directPeerId = identity.directPeerId,
                minecraftUuid = identity.uuid,
            )
        }
    }

    private fun startTimeout(request: PendingRequest) {
        val timeoutJob = scope.launch {
            delay(timeout)
            expire(request)
        }
        if (!request.timeoutJob.compareAndSet(null, timeoutJob)) {
            timeoutJob.cancel()
        } else if (request.answer.isCompleted) {
            timeoutJob.cancel()
        }
    }

    private fun expire(request: PendingRequest) {
        val expired = synchronized(lock) {
            if (requests[request.key] !== request) {
                return
            }
            requests.remove(request.key)
            publishPending()
            request
        }
        expired.answer.complete(AdmissionAnswer.TIMEOUT)
    }

    private fun complete(request: PendingRequest, answer: AdmissionAnswer) {
        request.timeoutJob.get()?.cancel()
        request.answer.complete(answer)
    }

    private fun releaseWaiter(request: PendingRequest) {
        val abandoned = synchronized(lock) {
            request.waiters--
            check(request.waiters >= 0) {
                "Admission request waiter count became negative"
            }
            if (
                request.waiters == 0 &&
                !request.answer.isCompleted &&
                requests[request.key] === request
            ) {
                requests.remove(request.key)
                publishPending()
                request
            } else {
                null
            }
        }
        abandoned?.timeoutJob?.get()?.cancel()
    }

    private fun publishPending() {
        mutablePending.value = requests.values.map(PendingRequest::pending)
    }

    private fun AdmissionIdentity.admissionKey(
        purpose: AdmissionPurpose,
    ): AdmissionKey = when (this) {
        is AdmissionIdentity.Authenticated ->
            AdmissionKey.Authenticated(uuid, purpose)

        is AdmissionIdentity.UnverifiedOffline ->
            AdmissionKey.Unverified(connectionId, purpose)
    }

    private sealed interface AdmissionKey {
        data class Authenticated(
            val uuid: UUID,
            val purpose: AdmissionPurpose,
        ) : AdmissionKey

        data class Unverified(
            val connectionId: String,
            val purpose: AdmissionPurpose,
        ) : AdmissionKey
    }

    private data class PreapprovedJoin(
        val directPeerId: String?,
        val minecraftUuid: UUID,
    ) {
        fun matches(identity: AdmissionIdentity): Boolean =
            minecraftUuid == identity.uuid &&
                (
                    directPeerId == identity.directPeerId ||
                        (
                            directPeerId != null &&
                                identity.directPeerId == null &&
                                when (identity) {
                                    is AdmissionIdentity.Authenticated ->
                                        identity.ingress == Ingress.CONNECT
                                    is AdmissionIdentity.UnverifiedOffline ->
                                        identity.ingress == Ingress.CONNECT
                                }
                            )
                    )
    }

    private data class AuthenticatedApproval(
        val uuid: UUID,
        val directPeerId: String?,
        val ingress: Ingress,
    ) {
        fun matches(identity: AdmissionIdentity.Authenticated): Boolean =
            uuid == identity.uuid &&
                (directPeerId == null || directPeerId == identity.directPeerId) &&
                (directPeerId != null || ingress == identity.ingress)
    }

    private class PendingRequest(
        val key: AdmissionKey,
        val pending: PendingAdmission,
        val answer: CompletableDeferred<AdmissionAnswer> = CompletableDeferred(),
        val timeoutJob: AtomicReference<Job?> = AtomicReference(),
        var waiters: Int = 1,
    )

    private sealed interface RequestLookup {
        data class Immediate(val answer: AdmissionAnswer) : RequestLookup
        data class Await(
            val request: PendingRequest,
            val startTimeout: Boolean,
        ) : RequestLookup
    }
}
