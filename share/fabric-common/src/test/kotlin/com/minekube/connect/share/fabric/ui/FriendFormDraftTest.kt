package com.minekube.connect.share.fabric.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendFormDraftTest {
    @Test
    fun `a new friend request never inherits another relationship`() {
        val previous = FriendFormDraft.forManage("Existing friend")
            .copy(
                invitation = "old invitation",
                offlineMode = true,
                internetDirect = true,
            )

        assertEquals(FriendFormDraft(), previous.newRequest())
    }

    @Test
    fun `managing a friend carries only that confirmed display name`() {
        val draft = FriendFormDraft.forManage("Robin")

        assertEquals("Robin", draft.displayName)
        assertTrue(draft.invitation.isEmpty())
        assertFalse(draft.offlineMode)
        assertFalse(draft.internetDirect)
    }
}
