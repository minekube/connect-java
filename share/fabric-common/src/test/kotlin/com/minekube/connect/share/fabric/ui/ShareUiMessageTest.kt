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
            "connect_share.login.authentication_required",
            ShareLoginMessages.AUTHENTICATION_REQUIRED,
        )
        assertEquals(
            "connect_share.login.approval_timed_out",
            ShareLoginMessages.denial(AdmissionAnswer.TIMEOUT),
        )
        assertEquals(
            "connect_share.login.share_full",
            ShareLoginMessages.denial(AdmissionAnswer.CAPACITY),
        )
        assertEquals(
            "connect_share.login.sharing_stopped",
            ShareLoginMessages.denial(AdmissionAnswer.STOPPED),
        )
        assertEquals(
            "connect_share.login.host_denied",
            ShareLoginMessages.denial(AdmissionAnswer.DENY),
        )
    }
}
