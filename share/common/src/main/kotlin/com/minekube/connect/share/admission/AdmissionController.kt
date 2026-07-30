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
    private val authenticatedApprovals = mutableSetOf<UUID>()
    private val mutablePending = MutableStateFlow<List<PendingAdmission>>(emptyList())

    val pending: StateFlow<List<PendingAdmission>> = mutablePending.asStateFlow()

    init {
        require(timeout.isPositive()) { "Admission timeout must be positive" }
        require(maxPending > 0) { "Maximum pending admissions must be positive" }
    }

    suspend fun request(identity: AdmissionIdentity): AdmissionAnswer {
        val lookup = synchronized(lock) {
            val key = identity.admissionKey()
            requests[key]?.let {
                return@synchronized RequestLookup.Await(it, startTimeout = false)
            }
            if (connectedCount() >= maxGuests()) {
                return@synchronized RequestLookup.Immediate(AdmissionAnswer.CAPACITY)
            }
            if (autoApprove(identity)) {
                return@synchronized RequestLookup.Immediate(AdmissionAnswer.ALLOW)
            }
            if (
                identity is AdmissionIdentity.Authenticated &&
                identity.uuid in authenticatedApprovals
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
                lookup.request.answer.await()
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
            if (allow) {
                val identity = entry.value.pending.identity
                if (identity is AdmissionIdentity.Authenticated) {
                    authenticatedApprovals += identity.uuid
                }
            }
            publishPending()
            entry.value
        }
        complete(completed, answer)
    }

    fun resetShare() {
        val stopped = synchronized(lock) {
            val current = requests.values.toList()
            requests.clear()
            authenticatedApprovals.clear()
            publishPending()
            current
        }
        stopped.forEach {
            complete(it, AdmissionAnswer.STOPPED)
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

    private fun publishPending() {
        mutablePending.value = requests.values.map(PendingRequest::pending)
    }

    private fun AdmissionIdentity.admissionKey(): AdmissionKey = when (this) {
        is AdmissionIdentity.Authenticated -> AdmissionKey.Authenticated(uuid)
        is AdmissionIdentity.UnverifiedOffline -> AdmissionKey.Unverified(connectionId)
    }

    private sealed interface AdmissionKey {
        data class Authenticated(val uuid: UUID) : AdmissionKey
        data class Unverified(val connectionId: String) : AdmissionKey
    }

    private class PendingRequest(
        val key: AdmissionKey,
        val pending: PendingAdmission,
        val answer: CompletableDeferred<AdmissionAnswer> = CompletableDeferred(),
        val timeoutJob: AtomicReference<Job?> = AtomicReference(),
    )

    private sealed interface RequestLookup {
        data class Immediate(val answer: AdmissionAnswer) : RequestLookup
        data class Await(
            val request: PendingRequest,
            val startTimeout: Boolean,
        ) : RequestLookup
    }
}
