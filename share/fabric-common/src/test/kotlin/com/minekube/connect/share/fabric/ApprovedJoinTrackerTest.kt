package com.minekube.connect.share.fabric

import com.minekube.connect.share.admission.AdmissionAnswer
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AuthSource
import com.minekube.connect.share.admission.Ingress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApprovedJoinTrackerTest {
    private var nowMillis = 1_000L
    private val tracker = ApprovedJoinTracker { nowMillis }

    @Test
    fun `approved authenticated identity can be consumed once`() {
        tracker.record(AUTHENTICATED, AdmissionAnswer.ALLOW)

        assertEquals(true, tracker.hasProof("Robin", PLAYER_UUID))
        assertEquals(
            PLAYER_UUID,
            tracker.consume("Robin", PLAYER_UUID)
                ?.authenticatedMinecraftUuid,
        )
        assertNull(tracker.consume("Robin", PLAYER_UUID))
    }

    @Test
    fun `approved offline identity proves pairing without trusting its uuid`() {
        tracker.record(OFFLINE, AdmissionAnswer.ALLOW)

        val proof = tracker.consume("Robin", PLAYER_UUID)

        assertNotNull(proof)
        assertNull(proof.authenticatedMinecraftUuid)
    }

    @Test
    fun `denied identities cannot trigger a friend card exchange`() {
        tracker.record(AUTHENTICATED, AdmissionAnswer.DENY)

        assertEquals(false, tracker.hasProof("Robin", PLAYER_UUID))
        assertNull(tracker.consume("Robin", PLAYER_UUID))
    }

    @Test
    fun `authentication proof expires before an unrelated later join`() {
        tracker.record(AUTHENTICATED, AdmissionAnswer.ALLOW)
        nowMillis += 121_000

        assertNull(tracker.consume("Robin", PLAYER_UUID))
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
