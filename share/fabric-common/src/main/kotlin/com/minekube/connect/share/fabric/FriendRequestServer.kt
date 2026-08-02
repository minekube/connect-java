package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionController
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AdmissionPurpose
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.ShareInviteCodec
import com.minekube.connect.share.friend.FriendControlContext
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendControlResponse
import com.minekube.connect.share.friend.FriendRemovalRequest
import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import com.minekube.connect.share.friend.FriendActivityRequest
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.FriendControlServer
import com.minekube.connect.share.friend.FriendAccessPolicy
import com.minekube.connect.share.friend.FriendRelationshipStatus
import com.minekube.connect.share.friend.FriendStore
import com.minekube.connect.share.friend.PresencePrivacy
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FriendRequestServer(
    private val scope: CoroutineScope,
    private val admission: AdmissionController,
    private val issuer: FriendCardIssuer,
    private val receiver: FriendCardReceiver,
    private val friendStore: FriendStore,
    private val approvedJoins: ApprovedJoinTracker? = null,
    private val now: () -> Instant = Instant::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onRelationshipChanged: () -> Unit = {},
    private val activity: () -> FriendActivity = {
        FriendActivity(FriendActivityKind.ONLINE)
    },
    private val presencePrivacy: () -> PresencePrivacy = {
        PresencePrivacy()
    },
    private val joinTarget: () -> String? = { null },
) : FriendControlServer {
    override fun allowsMinecraftStatus(
        context: FriendControlContext,
    ): Boolean {
        val friend = authenticatedFriend(context) ?: return false
        val privacy = presencePrivacy()
        return friend.permissions.canSeeMyWorlds &&
            privacy.showOnline &&
            privacy.showPlaying &&
            privacy.showCurrentServer
    }

    override fun handle(
        context: FriendControlContext,
        request: FriendControlRequest,
    ): CompletionStage<FriendControlResponse> {
        val result = CompletableFuture<FriendControlResponse>()
        val job = scope.launch(ioDispatcher) {
            try {
                result.complete(process(context, request))
            } catch (cancellation: CancellationException) {
                result.cancel(false)
                throw cancellation
            } catch (_: Exception) {
                result.complete(FriendControlResponse.Invalid)
            }
        }
        result.cancelJobWhenCancelled(job)
        return result
    }

    override fun handleRemoval(
        context: FriendControlContext,
        request: FriendRemovalRequest,
    ): CompletionStage<FriendControlResponse> {
        val result = CompletableFuture<FriendControlResponse>()
        val job = scope.launch(ioDispatcher) {
            try {
                val peerId = context.directPeerId
                val response = if (
                    context.ingress == Ingress.CONNECT || peerId == null
                ) {
                    FriendControlResponse.Invalid
                } else {
                    val removed = friendStore.applyRemoteRemoval(
                        peerId,
                        request.relationshipId,
                    )
                    if (removed != null) {
                        admission.revokeDirectPeer(
                            peerId,
                            removed.minecraftUuid,
                        )
                        approvedJoins?.revokeDirectPeer(
                            peerId,
                            removed.minecraftUuid,
                        )
                        notifyRelationshipChanged()
                    }
                    FriendControlResponse.Removed
                }
                result.complete(response)
            } catch (cancellation: CancellationException) {
                result.cancel(false)
                throw cancellation
            } catch (_: Exception) {
                result.complete(FriendControlResponse.Invalid)
            }
        }
        result.cancelJobWhenCancelled(job)
        return result
    }

    override fun handleActivity(
        context: FriendControlContext,
        request: FriendActivityRequest,
    ): CompletionStage<FriendControlResponse> = launchResponse {
        val friend = authenticatedFriend(context)
            ?: return@launchResponse FriendControlResponse.Invalid
        val privacy = presencePrivacy()
        if (!privacy.showOnline) {
            return@launchResponse FriendControlResponse.Invalid
        }
        val current = activity()
        val visible = when {
            !friend.permissions.canSeeMyWorlds || !privacy.showPlaying ->
                FriendActivity(FriendActivityKind.ONLINE)

            else -> current.copy(
                description = current.description.takeIf {
                    (
                        current.kind != FriendActivityKind.PLAYING_SERVER &&
                            current.kind != FriendActivityKind.HOSTING_WORLD
                        ) ||
                        privacy.showCurrentServer
                },
                joinable = current.joinable && privacy.showJoinable &&
                    friend.permissions.accessPolicy !=
                    FriendAccessPolicy.NEVER_ALLOW,
            )
        }
        FriendControlResponse.Activity(visible)
    }

    override fun handleJoin(
        context: FriendControlContext,
        request: FriendJoinRequest,
    ): CompletionStage<FriendControlResponse> = launchResponse {
        val friend = authenticatedFriend(context)
            ?: return@launchResponse FriendControlResponse.Invalid
        if (friend.permissions.accessPolicy == FriendAccessPolicy.NEVER_ALLOW) {
            return@launchResponse FriendControlResponse.Declined
        }
        if (!friend.permissions.canSeeMyWorlds) {
            return@launchResponse FriendControlResponse.Invalid
        }
        val currentActivity = activity()
        if (
            currentActivity.kind != FriendActivityKind.HOSTING_WORLD &&
            currentActivity.kind != FriendActivityKind.PLAYING_SERVER
        ) return@launchResponse FriendControlResponse.Invalid
        val identity = AdmissionIdentity.UnverifiedOffline(
            name = request.playerName,
            uuid = request.playerUuid,
            connectionId = "friend-join:${request.requestId}",
            ingress = context.ingress,
            directPeerId = context.directPeerId,
        )
        when (admission.request(identity, AdmissionPurpose.JOIN)) {
            AdmissionAnswer.ALLOW -> when (currentActivity.kind) {
                FriendActivityKind.HOSTING_WORLD -> {
                    admission.approveNextJoin(identity)
                    FriendControlResponse.SharedWorldJoinAccepted
                }
                FriendActivityKind.PLAYING_SERVER -> joinTarget()
                    ?.takeIf(String::isNotBlank)
                    ?.let(FriendControlResponse::JoinAccepted)
                    ?: FriendControlResponse.Invalid
                FriendActivityKind.ONLINE -> FriendControlResponse.Invalid
            }
            AdmissionAnswer.DENY -> FriendControlResponse.Declined
            AdmissionAnswer.TIMEOUT -> FriendControlResponse.TimedOut
            AdmissionAnswer.STOPPED,
            AdmissionAnswer.CAPACITY,
            -> FriendControlResponse.Invalid
        }
    }

    private fun authenticatedFriend(
        context: FriendControlContext,
    ) = context.directPeerId
        ?.takeIf { context.ingress != Ingress.CONNECT }
        ?.let(friendStore::relationship)
        ?.getOrNull()
        ?.takeIf {
            it.relationshipStatus == FriendRelationshipStatus.CONFIRMED
        }

    private fun launchResponse(
        operation: suspend () -> FriendControlResponse,
    ): CompletionStage<FriendControlResponse> {
        val result = CompletableFuture<FriendControlResponse>()
        val job = scope.launch(ioDispatcher) {
            try {
                result.complete(operation())
            } catch (cancellation: CancellationException) {
                result.cancel(false)
                throw cancellation
            } catch (_: Exception) {
                result.complete(FriendControlResponse.Invalid)
            }
        }
        result.cancelJobWhenCancelled(job)
        return result
    }

    private suspend fun process(
        context: FriendControlContext,
        request: FriendControlRequest,
    ): FriendControlResponse {
        val authenticatedPeerId = context.directPeerId
            ?: return FriendControlResponse.Invalid
        if (context.ingress == Ingress.CONNECT) {
            return FriendControlResponse.Invalid
        }
        val instant = now()
        val invitation = ShareInviteCodec.decode(
            request.invitation,
            instant,
        ).getOrNull() ?: return FriendControlResponse.Invalid
        val senderPeerId = invitation.payload.peerId
        if (authenticatedPeerId != senderPeerId) {
            return FriendControlResponse.Invalid
        }
        if (friendStore.isBlocked(senderPeerId)) {
            return FriendControlResponse.Declined
        }
        val senderKey = Base64.getEncoder()
            .encodeToString(invitation.publicKey)
        val existing = friendStore.relationship(senderPeerId).getOrNull()
        if (existing != null) {
            if (existing.publicKeyBase64 != senderKey) {
                return FriendControlResponse.Invalid
            }
            val accepted = receiver.receive(
                invitation = request.invitation,
                displayName = request.displayName,
                authenticatedMinecraftUuid = null,
                relationshipId = request.relationshipId,
                now = instant,
            )
            if (accepted.isLeft()) {
                return FriendControlResponse.Invalid
            }
            notifyRelationshipChanged()
            return issueHostCard(instant)
        }

        val identity = AdmissionIdentity.UnverifiedOffline(
            name = request.displayName,
            uuid = invitation.payload.shareId,
            connectionId = "friend:${request.requestId}",
            ingress = context.ingress,
            directPeerId = context.directPeerId,
        )
        return when (
            admission.request(
                identity,
                purpose = AdmissionPurpose.FRIEND,
            )
        ) {
            AdmissionAnswer.ALLOW -> {
                val received = receiver.receive(
                    invitation = request.invitation,
                    displayName = request.displayName,
                    authenticatedMinecraftUuid = null,
                    relationshipId = request.relationshipId,
                    now = instant,
                )
                if (received.isLeft()) {
                    FriendControlResponse.Invalid
                } else {
                    notifyRelationshipChanged()
                    issueHostCard(instant)
                }
            }

            AdmissionAnswer.DENY -> FriendControlResponse.Declined
            AdmissionAnswer.TIMEOUT -> FriendControlResponse.TimedOut
            AdmissionAnswer.STOPPED,
            AdmissionAnswer.CAPACITY,
            -> FriendControlResponse.Invalid
        }
    }

    private suspend fun issueHostCard(
        now: Instant,
    ): FriendControlResponse =
        issuer.issue(now).fold(
            ifLeft = { FriendControlResponse.Invalid },
            ifRight = FriendControlResponse::Accepted,
        )

    private fun notifyRelationshipChanged() {
        try {
            onRelationshipChanged()
        } catch (_: RuntimeException) {
            // A UI refresh must not undo an accepted friendship.
        }
    }

    private fun CompletableFuture<FriendControlResponse>.cancelJobWhenCancelled(
        job: Job,
    ) {
        whenComplete { _, _ ->
            if (isCancelled) {
                job.cancel()
            }
        }
    }
}
