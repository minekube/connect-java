package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.fabric.ui.FriendsViewModel
import com.minekube.connect.share.friend.FriendJoinRequest
import com.minekube.connect.share.friend.CompatibilityProfile
import com.minekube.connect.share.friend.CompatibilityReport
import com.minekube.connect.tunnel.p2p.DirectP2pAuthMode

sealed interface FriendJoinAttemptFailure {
    val safeMessage: String

    data class Control(
        val failure: GuestJoinFailure,
    ) : FriendJoinAttemptFailure {
        override val safeMessage: String = failure.safeMessage
    }

    data class Request(
        val failure: FriendRequestFailure,
    ) : FriendJoinAttemptFailure {
        override val safeMessage: String = failure.safeMessage
    }

    data class Gameplay(
        val failure: GuestJoinFailure,
    ) : FriendJoinAttemptFailure {
        override val safeMessage: String = failure.safeMessage
    }

    data class Compatibility(
        val report: CompatibilityReport.Mismatch,
    ) : FriendJoinAttemptFailure {
        override val safeMessage: String = report.safeMessage
        val canTryAnyway: Boolean = !report.hasHardBlock
    }
}

class FriendJoinOrchestrator private constructor(
    private val requestApproval: suspend (
        String,
        FriendJoinRequest,
    ) -> Either<FriendJoinAttemptFailure, FriendJoinApproval>,
    private val openSharedWorld: suspend (String) ->
        Either<FriendJoinAttemptFailure, GuestJoinTarget>,
    private val localCompatibility: () -> CompatibilityProfile?,
    private val remoteCompatibility: (String) -> CompatibilityProfile?,
    private val diagnostics: ShareJoinDiagnostics,
) {
    suspend fun request(
        peerId: String,
        request: FriendJoinRequest,
        allowModMismatch: Boolean = false,
    ): Either<FriendJoinAttemptFailure, GuestJoinTarget> {
        diagnostics.record(JoinStage.COMPATIBILITY, JoinOutcome.STARTED)
        val mismatch = compatibilityMismatch(peerId)
        if (
            mismatch != null &&
            (mismatch.hasHardBlock || !allowModMismatch)
        ) {
            diagnostics.record(JoinStage.COMPATIBILITY, JoinOutcome.FAILED)
            return FriendJoinAttemptFailure.Compatibility(mismatch).left()
        }
        diagnostics.record(JoinStage.COMPATIBILITY, JoinOutcome.SUCCEEDED)
        diagnostics.record(JoinStage.APPROVAL, JoinOutcome.STARTED)
        val approval = requestApproval(peerId, request).fold(
            ifLeft = { failure ->
                diagnostics.record(JoinStage.APPROVAL, JoinOutcome.FAILED)
                return failure.left()
            },
            ifRight = { it },
        )
        diagnostics.record(JoinStage.APPROVAL, JoinOutcome.SUCCEEDED)
        val target = when (approval) {
            is FriendJoinApproval.ExternalServer ->
                GuestJoinTarget.Connect(approval.address).right()

            FriendJoinApproval.SharedWorld -> openSharedWorld(peerId)
        }
        target.fold(
            ifLeft = {
                diagnostics.record(JoinStage.DIRECT, JoinOutcome.FAILED)
            },
            ifRight = { joined ->
                diagnostics.record(
                    when (joined) {
                        is GuestJoinTarget.Connect -> JoinStage.CONNECT_FALLBACK
                        is GuestJoinTarget.Direct -> JoinStage.DIRECT
                    },
                    JoinOutcome.SUCCEEDED,
                )
            },
        )
        return target
    }

    private fun compatibilityMismatch(
        peerId: String,
    ): CompatibilityReport.Mismatch? {
        val local = localCompatibility() ?: return null
        val remote = remoteCompatibility(peerId) ?: return null
        return local.compareTo(remote) as? CompatibilityReport.Mismatch
    }

    companion object {
        fun create(
            friends: FriendsViewModel,
            browser: FabricShareBrowser,
            requestClient: FriendRequestClient,
            ownConnectAddress: () -> String?,
            gameplayAuthMode: () -> DirectP2pAuthMode,
            localCompatibility: () -> CompatibilityProfile?,
            diagnostics: ShareJoinDiagnostics,
        ) = FriendJoinOrchestrator(
            requestApproval = { peerId, request ->
                friends.routeFriendControl(
                    peerId,
                    browser,
                    DirectP2pAuthMode.OFFLINE,
                ).mapLeft(FriendJoinAttemptFailure::Control)
                    .flatMap { target ->
                        requestClient.requestJoin(target, request)
                            .mapLeft(FriendJoinAttemptFailure::Request)
                    }
            },
            openSharedWorld = { peerId ->
                friends.join(
                    peerId = peerId,
                    browser = browser,
                    authMode = gameplayAuthMode(),
                    ownConnectAddress = ownConnectAddress(),
                ).mapLeft(FriendJoinAttemptFailure::Gameplay)
            },
            localCompatibility = localCompatibility,
            remoteCompatibility = friends::compatibilityFor,
            diagnostics = diagnostics,
        )

        internal fun testing(
            requestApproval: suspend (FriendJoinRequest) ->
                Either<FriendRequestFailure, FriendJoinApproval>,
            openSharedWorld: suspend (String) ->
                Either<GuestJoinFailure, GuestJoinTarget>,
            localCompatibility: () -> CompatibilityProfile? = { null },
            remoteCompatibility: (String) -> CompatibilityProfile? = { null },
            diagnostics: ShareJoinDiagnostics = ShareJoinDiagnostics(),
        ) = FriendJoinOrchestrator(
            requestApproval = { _, request ->
                requestApproval(request)
                    .mapLeft(FriendJoinAttemptFailure::Request)
            },
            openSharedWorld = { peerId ->
                openSharedWorld(peerId)
                    .mapLeft(FriendJoinAttemptFailure::Gameplay)
            },
            localCompatibility = localCompatibility,
            remoteCompatibility = remoteCompatibility,
            diagnostics = diagnostics,
        )
    }
}
