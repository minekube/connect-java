package com.minekube.connect.share.fabric.ui

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.direct.ShareInviteError
import com.minekube.connect.share.fabric.GuestJoinFailure
import com.minekube.connect.share.friend.FriendStoreError
import kotlin.test.Test
import kotlin.test.assertEquals

class ShareUiMessageTest {
    @Test
    fun `invitation failures retain their actionable reason`() {
        val cases = listOf(
            ShareInviteError.Malformed to ShareUiMessage(
                "connect_share.error.invitation_malformed",
            ),
            ShareInviteError.UnsupportedVersion(9) to ShareUiMessage(
                "connect_share.error.invitation_unsupported_version",
                listOf("9"),
            ),
            ShareInviteError.Expired to ShareUiMessage(
                "connect_share.error.invitation_expired",
            ),
            ShareInviteError.InvalidSignature to ShareUiMessage(
                "connect_share.error.invitation_invalid_signature",
            ),
            ShareInviteError.RelayCandidateForbidden to ShareUiMessage(
                "connect_share.error.invitation_relay_forbidden",
            ),
            ShareInviteError.PeerMismatch to ShareUiMessage(
                "connect_share.error.invitation_peer_mismatch",
            ),
        )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, failure.uiMessage())
            assertEquals(
                expected,
                FriendStoreError.InvalidInvitation(failure).uiMessage(),
            )
            assertEquals(
                expected,
                GuestJoinFailure.InvalidInvitation(failure).uiMessage(),
            )
        }
    }

    @Test
    fun `login denial messages are stable translation keys`() {
        assertEquals(
            RemoteLoginMessage(
                "connect_share.login.authentication_required",
                "This connection needs a valid Minecraft account.",
            ),
            ShareLoginMessages.AUTHENTICATION_REQUIRED,
        )
        assertEquals(
            RemoteLoginMessage(
                "connect_share.login.approval_timed_out",
                "The host did not approve this join in time. Try again.",
            ),
            ShareLoginMessages.denial(AdmissionAnswer.TIMEOUT),
        )
        assertEquals(
            RemoteLoginMessage(
                "connect_share.login.share_full",
                "This shared world is full. Ask the host to make room.",
            ),
            ShareLoginMessages.denial(AdmissionAnswer.CAPACITY),
        )
        assertEquals(
            RemoteLoginMessage(
                "connect_share.login.sharing_stopped",
                "This world is not available right now. Ask the host to share it again.",
            ),
            ShareLoginMessages.denial(AdmissionAnswer.STOPPED),
        )
        assertEquals(
            RemoteLoginMessage(
                "connect_share.login.host_denied",
                "The host declined this join. Request access again when ready.",
            ),
            ShareLoginMessages.denial(AdmissionAnswer.DENY),
        )
    }
}
