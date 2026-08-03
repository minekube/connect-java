package com.minekube.connect.share.friend

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SharePreferencesStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `share with friends remains enabled across restarts until disabled`() {
        val store = SharePreferencesStore(tempDir)

        assertFalse(store.load().shareWithFriends)

        store.save(SharePreferences(shareWithFriends = true))
        assertTrue(SharePreferencesStore(tempDir).load().shareWithFriends)

        store.save(SharePreferences(shareWithFriends = false))
        assertFalse(SharePreferencesStore(tempDir).load().shareWithFriends)
    }

    @Test
    fun `independent presence privacy choices survive restart`() {
        val preferences = SharePreferences(
            shareWithFriends = true,
            presence = PresencePrivacy(
                showOnline = true,
                showPlaying = false,
                showCurrentServer = false,
                showJoinable = true,
            ),
        )

        SharePreferencesStore(tempDir).save(preferences)

        assertEquals(preferences, SharePreferencesStore(tempDir).load())
    }
}
