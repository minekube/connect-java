package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AuthSource
import com.minekube.connect.share.admission.Ingress
import com.minekube.connect.share.direct.ShareInviteCodec
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class ApprovedJoinTrackerTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private var nowMillis = 1_000L
    private val tracker = ApprovedJoinTracker { nowMillis }

    @Test
    fun `approved authenticated identity can be consumed once`() = runTest {
        val (invitation, peerId) = invitationAndPeer()
        tracker.record(
            AUTHENTICATED.copy(directPeerId = peerId),
            AdmissionAnswer.ALLOW,
        )

        assertEquals(true, tracker.hasProof("Robin", PLAYER_UUID))
        assertEquals(
            PLAYER_UUID,
            tracker.consume("Robin", PLAYER_UUID, invitation)
                ?.authenticatedMinecraftUuid,
        )
        assertNull(tracker.consume("Robin", PLAYER_UUID, invitation))
    }

    @Test
    fun `approved offline identity proves pairing without trusting its uuid`() = runTest {
        val (invitation, peerId) = invitationAndPeer()
        tracker.record(
            OFFLINE.copy(directPeerId = peerId),
            AdmissionAnswer.ALLOW,
        )

        val proof = tracker.consume("Robin", PLAYER_UUID, invitation)

        assertNotNull(proof)
        assertNull(proof.authenticatedMinecraftUuid)
    }

    @Test
    fun `denied identities cannot trigger a friend card exchange`() {
        tracker.record(AUTHENTICATED, AdmissionAnswer.DENY)

        assertEquals(false, tracker.hasProof("Robin", PLAYER_UUID))
        assertNull(tracker.consume("Robin", PLAYER_UUID, "invalid"))
    }

    @Test
    fun `authentication proof expires before an unrelated later join`() = runTest {
        val (invitation, peerId) = invitationAndPeer()
        tracker.record(
            AUTHENTICATED.copy(directPeerId = peerId),
            AdmissionAnswer.ALLOW,
        )
        nowMillis += 121_000

        assertNull(tracker.consume("Robin", PLAYER_UUID, invitation))
    }

    @Test
    fun `removing a peer revokes its direct and linked uuid proofs`() {
        val peerId = "12D3KooWRemovedFriend"
        tracker.record(
            AUTHENTICATED.copy(directPeerId = peerId),
            AdmissionAnswer.ALLOW,
        )
        tracker.record(
            AUTHENTICATED.copy(name = "LinkedConnectPlayer"),
            AdmissionAnswer.ALLOW,
        )

        assertEquals(2, tracker.revokeDirectPeer(peerId, PLAYER_UUID))
        assertEquals(false, tracker.hasProof("Robin", PLAYER_UUID))
        assertEquals(false, tracker.hasProof("LinkedConnectPlayer", PLAYER_UUID))
    }

    @Test
    fun `automatic friendship proof requires the matching direct peer`() = runTest {
        val (expectedInvitation, expectedPeerId) = invitationAndPeer()
        val (otherInvitation, _) = invitationAndPeer("other")
        tracker.record(
            OFFLINE.copy(directPeerId = expectedPeerId),
            AdmissionAnswer.ALLOW,
        )

        assertNull(
            tracker.consume("Robin", PLAYER_UUID, otherInvitation),
            "a different signed peer must not consume another peer's approval",
        )
        assertNull(
            tracker.consume("Robin", PLAYER_UUID, expectedInvitation),
            "a rejected proof remains one-shot",
        )
    }

    @Test
    fun `unbound Connect proof cannot enable automatic friendship`() = runTest {
        val (invitation, _) = invitationAndPeer()
        tracker.record(OFFLINE, AdmissionAnswer.ALLOW)

        assertEquals(false, tracker.hasProof("Robin", PLAYER_UUID))
        assertNull(tracker.consume("Robin", PLAYER_UUID, invitation))
    }

    private suspend fun invitationAndPeer(
        suffix: String = "expected",
    ): Pair<String, String> {
        val invitation = FriendCardIssuer(
            dataDirectory = tempDir.resolve(suffix),
            connectAddress = { null },
        ).issue(Instant.ofEpochMilli(nowMillis)).getOrNull()!!
        val peerId = ShareInviteCodec.decode(
            invitation,
            Instant.ofEpochMilli(nowMillis),
        ).getOrNull()!!.payload.peerId
        return invitation to peerId
    }

    private companion object {
        val PLAYER_UUID: UUID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val AUTHENTICATED = AdmissionIdentity.Authenticated(
            name = "Robin",
            uuid = PLAYER_UUID,
            source = AuthSource.CONNECT,
        )
        val OFFLINE = AdmissionIdentity.UnverifiedOffline(
            name = "Robin",
            uuid = PLAYER_UUID,
            connectionId = "offline-connection",
            ingress = Ingress.CONNECT,
        )
    }
}
