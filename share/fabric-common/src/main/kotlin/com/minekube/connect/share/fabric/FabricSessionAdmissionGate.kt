package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AuthSource
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.watch.SessionAdmissionDecision
import com.minekube.connect.watch.SessionAdmissionGate
import com.minekube.connect.watch.SessionProposal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FabricSessionAdmissionGate(
    private val admission: AdmissionController,
    private val scope: CoroutineScope,
    private val approvedJoins: ApprovedJoinTracker =
        ApprovedJoinTracker(),
    private val worldAvailable: () -> Boolean = { true },
) : SessionAdmissionGate {
    private val stopped = AtomicBoolean()
    private val active = ConcurrentHashMap<CompletableFuture<SessionAdmissionDecision>, Job>()

    override fun request(
        proposal: SessionProposal,
    ): CompletionStage<SessionAdmissionDecision> {
        if (proposal.isStatusProbe()) {
            return CompletableFuture.completedFuture(
                SessionAdmissionDecision.allow(),
            )
        }
        if (!worldAvailable()) {
            return CompletableFuture.completedFuture(
                SessionAdmissionDecision.deny(NO_SHARED_WORLD),
            )
        }
        if (proposal.session.auth.passthrough) {
            return CompletableFuture.completedFuture(
                SessionAdmissionDecision.deferToLocalLogin(),
            )
        }
        val identity = authenticatedIdentity(proposal).fold(
            ifLeft = {
                return CompletableFuture.completedFuture(
                    SessionAdmissionDecision.deny(INVALID_PROFILE),
                )
            },
            ifRight = { it },
        )
        val future = CompletableFuture<SessionAdmissionDecision>()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val answer = admission.request(identity)
                approvedJoins.record(identity, answer)
                future.complete(answer.toCoreDecision())
            } catch (cancellation: CancellationException) {
                future.cancel(false)
                throw cancellation
            } catch (_: Exception) {
                future.complete(SessionAdmissionDecision.deny(ADMISSION_FAILED))
            } finally {
                active.remove(future)
            }
        }
        active[future] = job
        job.invokeOnCompletion { failure ->
            active.remove(future)
            if (failure is CancellationException && !future.isDone) {
                future.cancel(false)
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) {
                job.cancel()
            }
        }
        if (stopped.get()) {
            active.remove(future)
            future.cancel(false)
            job.cancel()
        } else {
            job.start()
        }
        return future
    }

    private fun SessionProposal.isStatusProbe(): Boolean {
        val session = session
        return !session.hasPlayer() ||
            !session.player.hasProfile() ||
            session.player.profile.name.isBlank()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        active.forEach { (future, job) ->
            future.cancel(false)
            job.cancel()
        }
        active.clear()
    }

    private fun authenticatedIdentity(
        proposal: SessionProposal,
    ): Either<InvalidProfile, AdmissionIdentity.Authenticated> = either {
        val session = proposal.session
        ensure(session.hasPlayer() && session.player.hasProfile()) { InvalidProfile }
        val profile = session.player.profile
        ensure(profile.name.isNotBlank()) { InvalidProfile }
        val uuid = Either.catch {
            UUID.fromString(profile.id)
        }.mapLeft { InvalidProfile }.bind()
        AdmissionIdentity.Authenticated(
            name = profile.name,
            uuid = uuid,
            source = AuthSource.CONNECT,
        )
    }

    private fun AdmissionAnswer.toCoreDecision(): SessionAdmissionDecision = when (this) {
        AdmissionAnswer.ALLOW -> SessionAdmissionDecision.allow()
        AdmissionAnswer.DENY -> SessionAdmissionDecision.deny("Host denied this connection")
        AdmissionAnswer.TIMEOUT -> SessionAdmissionDecision.deny("Host approval timed out")
        AdmissionAnswer.STOPPED -> SessionAdmissionDecision.deny("Sharing stopped")
        AdmissionAnswer.CAPACITY -> SessionAdmissionDecision.deny("Share is full")
    }

    private companion object {
        data object InvalidProfile
        const val INVALID_PROFILE = "Connect profile is invalid"
        const val ADMISSION_FAILED = "Could not ask the host for approval"
        const val NO_SHARED_WORLD = "No shared world is active"
    }
}

class FabricLocalLoginAdmission(
    private val admission: AdmissionController,
    private val approvedJoins: ApprovedJoinTracker =
        ApprovedJoinTracker(),
) {
    suspend fun request(
        name: String,
        uuid: UUID,
        connectionId: String,
        minecraftAuthenticated: Boolean,
        ingress: Ingress = Ingress.CONNECT,
        directPeerId: String? = null,
    ): AdmissionAnswer {
        val identity = if (minecraftAuthenticated) {
            AdmissionIdentity.Authenticated(
                name = name,
                uuid = uuid,
                source = AuthSource.MOJANG,
                ingress = ingress,
                directPeerId = directPeerId,
            )
        } else {
            AdmissionIdentity.UnverifiedOffline(
                name = name,
                uuid = uuid,
                connectionId = connectionId,
                ingress = ingress,
                directPeerId = directPeerId,
            )
        }
        return admission.request(identity).also { answer ->
            approvedJoins.record(identity, answer)
        }
    }
}

class FabricLocalLoginAdmissionGate(
    private val admission: FabricLocalLoginAdmission,
    private val scope: CoroutineScope,
) {
    private val stopped = AtomicBoolean()
    private val active = ConcurrentHashMap<CompletableFuture<AdmissionAnswer>, Job>()

    fun request(
        name: String,
        uuid: UUID,
        connectionId: String,
        minecraftAuthenticated: Boolean,
        ingress: Ingress = Ingress.CONNECT,
        directPeerId: String? = null,
    ): CompletionStage<AdmissionAnswer> {
        val future = CompletableFuture<AdmissionAnswer>()
        if (stopped.get()) {
            future.cancel(false)
            return future
        }

        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                future.complete(
                    admission.request(
                        name = name,
                        uuid = uuid,
                        connectionId = connectionId,
                        minecraftAuthenticated = minecraftAuthenticated,
                        ingress = ingress,
                        directPeerId = directPeerId,
                    ),
                )
            } catch (cancellation: CancellationException) {
                future.cancel(false)
                throw cancellation
            } catch (_: Exception) {
                future.complete(AdmissionAnswer.DENY)
            } finally {
                active.remove(future)
            }
        }
        active[future] = job
        job.invokeOnCompletion { failure ->
            active.remove(future)
            if (failure is CancellationException && !future.isDone) {
                future.cancel(false)
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) {
                job.cancel()
            }
        }
        if (stopped.get()) {
            active.remove(future)
            future.cancel(false)
            job.cancel()
        } else {
            job.start()
        }
        return future
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            return
        }
        active.forEach { (future, job) ->
            future.cancel(false)
            job.cancel()
        }
        active.clear()
    }
}
