package com.minekube.connect.share.fabric.ui

import com.minekube.connect.share.ShareLifecycleError
import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.direct.ShareInviteError
import com.minekube.connect.share.fabric.FriendJoinAttemptFailure
import com.minekube.connect.share.fabric.FriendRequestFailure
import com.minekube.connect.share.fabric.GuestJoinFailure
import com.minekube.connect.share.friend.CompatibilityDifference
import com.minekube.connect.share.friend.FriendStoreError
import com.minekube.connect.share.identity.CredentialValidationError

data class ShareUiMessage(
    val translationKey: String,
    val arguments: List<String> = emptyList(),
)

object ShareLoginMessages {
    const val AUTHENTICATION_REQUIRED =
        "connect_share.login.authentication_required"

    fun denial(answer: AdmissionAnswer?): String = when (answer) {
        AdmissionAnswer.TIMEOUT ->
            "connect_share.login.approval_timed_out"
        AdmissionAnswer.CAPACITY ->
            "connect_share.login.share_full"
        AdmissionAnswer.STOPPED ->
            "connect_share.login.sharing_stopped"
        else -> "connect_share.login.host_denied"
    }
}

fun ShareInviteError.uiMessage(): ShareUiMessage = when (this) {
    ShareInviteError.Malformed ->
        ShareUiMessage("connect_share.error.invitation_malformed")
    is ShareInviteError.UnsupportedVersion -> ShareUiMessage(
        "connect_share.error.invitation_unsupported_version",
        listOf(version.toString()),
    )
    ShareInviteError.Expired ->
        ShareUiMessage("connect_share.error.invitation_expired")
    ShareInviteError.InvalidSignature ->
        ShareUiMessage("connect_share.error.invitation_invalid_signature")
    ShareInviteError.RelayCandidateForbidden ->
        ShareUiMessage("connect_share.error.invitation_relay_forbidden")
    ShareInviteError.PeerMismatch ->
        ShareUiMessage("connect_share.error.invitation_peer_mismatch")
}

fun FriendStoreError.uiMessage(): ShareUiMessage = when (this) {
    is FriendStoreError.InvalidInvitation ->
        reason.uiMessage()
    FriendStoreError.InvalidDisplayName ->
        ShareUiMessage("connect_share.error.invalid_friend_name")
    FriendStoreError.IdentityConflict ->
        ShareUiMessage("connect_share.error.friend_identity_conflict")
    FriendStoreError.NotFound ->
        ShareUiMessage("connect_share.error.friend_not_saved")
    FriendStoreError.Blocked ->
        ShareUiMessage("connect_share.error.friend_blocked")
}

fun CredentialValidationError.uiMessage(): ShareUiMessage = when (this) {
    is CredentialValidationError.InvalidInput ->
        ShareUiMessage("connect_share.error.identity_invalid")
    is CredentialValidationError.Rejected ->
        ShareUiMessage("connect_share.error.identity_rejected")
    is CredentialValidationError.Network ->
        ShareUiMessage("connect_share.error.identity_network")
    is CredentialValidationError.ManagedByEnvironment ->
        ShareUiMessage("connect_share.error.identity_managed")
}

fun ShareLifecycleError.uiMessage(): ShareUiMessage = when (this) {
    ShareLifecycleError.AlreadyActive ->
        ShareUiMessage("connect_share.error.share_already_active")
    ShareLifecycleError.StartFailed ->
        ShareUiMessage("connect_share.error.share_start_failed")
    ShareLifecycleError.StopFailed ->
        ShareUiMessage("connect_share.error.share_stop_failed")
}

fun GuestJoinFailure.uiMessage(): ShareUiMessage = when (this) {
    is GuestJoinFailure.InvalidInvitation ->
        error.uiMessage()
    GuestJoinFailure.PeerMismatch ->
        ShareUiMessage("connect_share.error.join_peer_mismatch")
    GuestJoinFailure.DiscoveryUnavailable ->
        ShareUiMessage("connect_share.error.join_discovery_unavailable")
    GuestJoinFailure.NoRoute ->
        ShareUiMessage("connect_share.error.join_no_route")
    GuestJoinFailure.EndpointConflict ->
        ShareUiMessage("connect_share.error.identity_endpoint_conflict")
}

fun FriendRequestFailure.uiMessage(): ShareUiMessage = when (this) {
    FriendRequestFailure.Unreachable ->
        ShareUiMessage("connect_share.error.friend_unreachable")
    FriendRequestFailure.Declined ->
        ShareUiMessage("connect_share.error.friend_declined")
    FriendRequestFailure.TimedOut ->
        ShareUiMessage("connect_share.error.friend_timed_out")
    FriendRequestFailure.InvalidResponse ->
        ShareUiMessage("connect_share.error.friend_invalid_response")
}

fun FriendJoinAttemptFailure.uiMessage(): ShareUiMessage = when (this) {
    is FriendJoinAttemptFailure.Control -> failure.uiMessage()
    is FriendJoinAttemptFailure.Request -> failure.uiMessage()
    is FriendJoinAttemptFailure.Gameplay -> failure.uiMessage()
    is FriendJoinAttemptFailure.Compatibility ->
        when {
            report.differences.any {
                it is CompatibilityDifference.MinecraftVersion
            } -> ShareUiMessage("connect_share.error.minecraft_version")
            report.differences.any {
                it is CompatibilityDifference.Loader
            } -> ShareUiMessage("connect_share.error.mod_loader")
            else -> ShareUiMessage("connect_share.error.required_mods")
        }
}

val GENERIC_SHARE_UI_MESSAGE =
    ShareUiMessage("connect_share.error.generic")
