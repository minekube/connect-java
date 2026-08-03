package com.minekube.connect.share.recovery

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RecoveryArchiveTest {
    private val archive = RecoveryArchive.testing(iterations = 10)

    @Test
    fun `encrypted archive round trips every allowlisted recovery entry`() {
        val encrypted = archive.encrypt(entries(), PASSPHRASE.copyOf())
            .getOrNull()!!
        val restored = archive.decrypt(encrypted, PASSPHRASE.copyOf())
            .getOrNull()!!

        assertEquals(entries().map { it.fileName }, restored.map { it.fileName })
        entries().zip(restored).forEach { (expected, actual) ->
            assertContentEquals(expected.contents, actual.contents)
        }
        assertFalse(encrypted.decodeToString().contains("friend-secret"))
    }

    @Test
    fun `wrong secret and tampering share one authentication failure`() {
        val encrypted = archive.encrypt(entries(), PASSPHRASE.copyOf())
            .getOrNull()!!

        assertIs<RecoveryArchiveError.AuthenticationFailed>(
            archive.decrypt(encrypted, "incorrect recovery secret".toCharArray())
                .leftOrNull(),
        )
        val tampered = encrypted.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertIs<RecoveryArchiveError.AuthenticationFailed>(
            archive.decrypt(tampered, PASSPHRASE.copyOf()).leftOrNull(),
        )
    }

    @Test
    fun `unsupported and oversized envelopes fail before decryption`() {
        val encrypted = archive.encrypt(entries(), PASSPHRASE.copyOf())
            .getOrNull()!!
        val unsupported = encrypted.copyOf().also {
            it[RecoveryArchive.VERSION_OFFSET] = 99
        }

        assertIs<RecoveryArchiveError.UnsupportedVersion>(
            archive.decrypt(unsupported, PASSPHRASE.copyOf()).leftOrNull(),
        )
        assertIs<RecoveryArchiveError.ArchiveTooLarge>(
            archive.decrypt(
                ByteArray(RecoveryArchive.MAX_ARCHIVE_BYTES + 1),
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
    }

    @Test
    fun `weak passphrase never starts encryption or decryption`() {
        assertIs<RecoveryArchiveError.WeakPassphrase>(
            archive.encrypt(entries(), "short".toCharArray()).leftOrNull(),
        )
        assertIs<RecoveryArchiveError.WeakPassphrase>(
            archive.decrypt(byteArrayOf(), CharArray(0)).leftOrNull(),
        )
    }

    @Test
    fun `unknown duplicate oversized and missing required entries are rejected`() {
        assertIs<RecoveryArchiveError.UnknownEntry>(
            archive.encrypt(
                entries() + RecoveryEntry("latest.log", byteArrayOf(1)),
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
        assertIs<RecoveryArchiveError.DuplicateEntry>(
            archive.encrypt(
                entries() + entries().first(),
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
        assertIs<RecoveryArchiveError.EntryTooLarge>(
            archive.encrypt(
                entries().map {
                    if (it.fileName == RecoveryArchive.FRIENDS_FILE) {
                        it.copy(
                            contents = ByteArray(
                                RecoveryArchive.MAX_ENTRY_BYTES + 1,
                            ),
                        )
                    } else {
                        it
                    }
                },
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
        assertIs<RecoveryArchiveError.MissingRequiredEntry>(
            archive.encrypt(
                entries().filterNot {
                    it.fileName == RecoveryArchive.SOCIAL_IDENTITY_FILE
                },
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
        assertIs<RecoveryArchiveError.IncompleteEndpointIdentity>(
            archive.encrypt(
                entries().filterNot {
                    it.fileName == RecoveryArchive.ENDPOINT_TOKEN_FILE
                },
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
    }

    private fun entries(): List<RecoveryEntry> = listOf(
        RecoveryEntry(
            RecoveryArchive.SOCIAL_IDENTITY_FILE,
            "social-private-key".encodeToByteArray(),
        ),
        RecoveryEntry(
            RecoveryArchive.GAMEPLAY_IDENTITY_FILE,
            "gameplay-private-key".encodeToByteArray(),
        ),
        RecoveryEntry(
            RecoveryArchive.ACCESS_IDENTITY_FILE,
            "{\"capability\":\"friend-secret\"}".encodeToByteArray(),
        ),
        RecoveryEntry(
            RecoveryArchive.FRIENDS_FILE,
            "{\"friends\":[\"friend-secret\"]}".encodeToByteArray(),
        ),
        RecoveryEntry(
            RecoveryArchive.PREFERENCES_FILE,
            "{\"shareWithFriends\":true}".encodeToByteArray(),
        ),
        RecoveryEntry(
            RecoveryArchive.ENDPOINT_CONFIG_FILE,
            "{\"endpoint\":\"redacted\"}".encodeToByteArray(),
        ),
        RecoveryEntry(
            RecoveryArchive.ENDPOINT_TOKEN_FILE,
            "{\"token\":\"endpoint-secret\"}".encodeToByteArray(),
        ),
    )

    private companion object {
        val PASSPHRASE = "correct horse battery staple".toCharArray()
    }
}
