package com.minekube.connect.share.admission

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewAdmissionTrackerTest {
    @Test
    fun `only newly pending requests produce notifications`() {
        val tracker = NewAdmissionTracker()
        val first = pending("Alex")
        val second = pending("Steve")

        assertEquals(listOf(first), tracker.update(listOf(first)))
        assertTrue(tracker.update(listOf(first)).isEmpty())
        assertEquals(
            listOf(second),
            tracker.update(listOf(first, second)),
        )
        assertTrue(tracker.update(emptyList()).isEmpty())
    }

    private fun pending(name: String) = PendingAdmission(
        requestId = UUID.randomUUID(),
        identity = AdmissionIdentity.UnverifiedOffline(
            name = name,
            uuid = UUID.randomUUID(),
            connectionId = UUID.randomUUID().toString(),
            ingress = Ingress.DIRECT_LAN,
        ),
    )
}
