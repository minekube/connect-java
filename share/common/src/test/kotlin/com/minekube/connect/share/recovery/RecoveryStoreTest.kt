package com.minekube.connect.share.recovery

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RecoveryStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `offline export and import restore the complete allowlisted state`() {
        val source = tempDir.resolve("source").createDirectories()
        val destination = tempDir.resolve("destination").createDirectories()
        seed(source, "source")
        seed(destination, "destination")
        val backup = tempDir.resolve("friends.connect-share-backup")

        val exported = store(source).exportTo(backup, PASSPHRASE.copyOf())
            .getOrNull()!!
        val imported = store(destination).importFrom(
            backup,
            PASSPHRASE.copyOf(),
        ).getOrNull()!!

        assertEquals(7, exported.entryCount)
        assertEquals(exported, imported)
        RecoveryStore.FILE_NAMES.forEach { fileName ->
            assertContentEquals(
                source.resolve(fileName).readBytes(),
                destination.resolve(fileName).readBytes(),
            )
        }
    }

    @Test
    fun `atomic export replaces an existing backup with a complete archive`() {
        val source = tempDir.resolve("source").createDirectories()
        seed(source, "source")
        val backup = tempDir.resolve("backup.bin").also {
            it.writeBytes("incomplete old backup".encodeToByteArray())
        }

        val result = store(source).exportTo(backup, PASSPHRASE.copyOf())

        assertEquals(7, result.getOrNull()!!.entryCount)
        assertEquals(
            7,
            testArchive().decrypt(backup.readBytes(), PASSPHRASE.copyOf())
                .getOrNull()!!
                .size,
        )
    }

    @Test
    fun `export cannot overwrite live Share recovery material`() {
        val source = tempDir.resolve("source").createDirectories()
        seed(source, "source")
        val friends = source.resolve(RecoveryArchive.FRIENDS_FILE)
        val original = friends.readBytes()

        assertIs<RecoveryStoreError.BackupWriteFailed>(
            store(source).exportTo(friends, PASSPHRASE.copyOf()).leftOrNull(),
        )
        assertContentEquals(original, friends.readBytes())
    }

    @Test
    fun `optional files omitted by the backup are removed on restore`() {
        val source = tempDir.resolve("source").createDirectories()
        val destination = tempDir.resolve("destination").createDirectories()
        seed(source, "source")
        seed(destination, "destination")
        val optional = setOf(
            RecoveryArchive.PREFERENCES_FILE,
            RecoveryArchive.ENDPOINT_CONFIG_FILE,
            RecoveryArchive.ENDPOINT_TOKEN_FILE,
        )
        optional.forEach { Files.delete(source.resolve(it)) }
        val backup = tempDir.resolve("backup.bin")

        val exported = store(source).exportTo(backup, PASSPHRASE.copyOf())
            .getOrNull()!!
        val imported = store(destination).importFrom(
            backup,
            PASSPHRASE.copyOf(),
        ).getOrNull()!!

        assertEquals(4, exported.entryCount)
        assertEquals(exported, imported)
        optional.forEach { assertFalse(Files.exists(destination.resolve(it))) }
    }

    @Test
    fun `wrong secret and malformed backup leave live files unchanged`() {
        val source = tempDir.resolve("source").createDirectories()
        val destination = tempDir.resolve("destination").createDirectories()
        seed(source, "source")
        seed(destination, "destination")
        val original = snapshot(destination)
        val backup = tempDir.resolve("backup.bin")
        store(source).exportTo(backup, PASSPHRASE.copyOf())

        assertIs<RecoveryStoreError.ArchiveFailure>(
            store(destination).importFrom(
                backup,
                "incorrect recovery secret".toCharArray(),
            ).leftOrNull(),
        )
        assertEquals(original, snapshot(destination))

        backup.writeBytes(byteArrayOf(1, 2, 3))
        assertIs<RecoveryStoreError.ArchiveFailure>(
            store(destination).importFrom(
                backup,
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
        assertEquals(original, snapshot(destination))
    }

    @Test
    fun `preview authenticates and summarizes without changing live files`() {
        val source = tempDir.resolve("source").createDirectories()
        val destination = tempDir.resolve("destination").createDirectories()
        seed(source, "source")
        seed(destination, "destination")
        val original = snapshot(destination)
        val backup = tempDir.resolve("backup.bin")
        store(source).exportTo(backup, PASSPHRASE.copyOf())

        val preview = store(destination).preview(
            backup,
            PASSPHRASE.copyOf(),
        ).getOrNull()!!

        assertEquals(7, preview.entryCount)
        assertTrue(preview.includesPreferences)
        assertTrue(preview.includesEndpointIdentity)
        assertEquals(original, snapshot(destination))
    }

    @Test
    fun `replacement failure rolls every changed file back`() {
        val source = tempDir.resolve("source").createDirectories()
        val destination = tempDir.resolve("destination").createDirectories()
        seed(source, "source")
        seed(destination, "destination")
        val original = snapshot(destination)
        val backup = tempDir.resolve("backup.bin")
        store(source).exportTo(backup, PASSPHRASE.copyOf())

        val failing = RecoveryStore(
            directory = destination,
            archive = testArchive(),
            beforeReplace = { index ->
                if (index == 2) error("injected replacement failure")
            },
        )

        assertIs<RecoveryStoreError.ReplacementFailed>(
            failing.importFrom(backup, PASSPHRASE.copyOf()).leftOrNull(),
        )
        assertEquals(original, snapshot(destination))
        assertFalse(Files.exists(destination.resolve(RecoveryStore.TRANSACTION_DIRECTORY)))
    }

    @Test
    fun `a new operation rolls back an interrupted transaction`() {
        val source = tempDir.resolve("source").createDirectories()
        val destination = tempDir.resolve("destination").createDirectories()
        seed(source, "source")
        seed(destination, "destination")
        val original = snapshot(destination)
        val backup = tempDir.resolve("backup.bin")
        store(source).exportTo(backup, PASSPHRASE.copyOf())
        val interrupted = RecoveryStore(
            directory = destination,
            archive = testArchive(),
            beforeReplace = { index ->
                if (index == 2) throw SimulatedPowerLoss
            },
        )

        assertFailsWith<SimulatedPowerLoss> {
            interrupted.importFrom(backup, PASSPHRASE.copyOf())
        }
        assertTrue(Files.exists(destination.resolve(RecoveryStore.TRANSACTION_DIRECTORY)))

        store(destination).exportTo(
            tempDir.resolve("after-recovery.bin"),
            PASSPHRASE.copyOf(),
        )
        assertEquals(original, snapshot(destination))
        assertFalse(Files.exists(destination.resolve(RecoveryStore.TRANSACTION_DIRECTORY)))
    }

    @Test
    fun `export rejects symlinks and missing required material`() {
        val source = tempDir.resolve("source").createDirectories()
        seed(source, "source")
        Files.delete(source.resolve(RecoveryArchive.FRIENDS_FILE))

        assertIs<RecoveryStoreError.MissingRequiredMaterial>(
            store(source).exportTo(
                tempDir.resolve("missing.bin"),
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )

        source.resolve(RecoveryArchive.FRIENDS_FILE).writeBytes(byteArrayOf(1))
        val linkTarget = tempDir.resolve("outside.key").also {
            it.writeBytes(byteArrayOf(2))
        }
        Files.delete(source.resolve(RecoveryArchive.SOCIAL_IDENTITY_FILE))
        try {
            Files.createSymbolicLink(
                source.resolve(RecoveryArchive.SOCIAL_IDENTITY_FILE),
                linkTarget,
            )
        } catch (_: UnsupportedOperationException) {
            return
        }
        assertIs<RecoveryStoreError.UnsafeMaterial>(
            store(source).exportTo(
                tempDir.resolve("symlink.bin"),
                PASSPHRASE.copyOf(),
            ).leftOrNull(),
        )
    }

    @Test
    fun `backup is owner only where posix permissions are available`() {
        val source = tempDir.resolve("source").createDirectories()
        seed(source, "source")
        val backup = tempDir.resolve("backup.bin")

        store(source).exportTo(backup, PASSPHRASE.copyOf())

        val view = Files.getFileAttributeView(
            backup,
            java.nio.file.attribute.PosixFileAttributeView::class.java,
        ) ?: return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            view.readAttributes().permissions(),
        )
    }

    private fun store(directory: Path) = RecoveryStore(
        directory = directory,
        archive = testArchive(),
    )

    private fun testArchive() = RecoveryArchive.testing(iterations = 10)

    private fun seed(directory: Path, prefix: String) {
        RecoveryStore.FILE_NAMES.forEach { fileName ->
            directory.resolve(fileName).writeBytes(
                "$prefix-$fileName".encodeToByteArray(),
            )
        }
    }

    private fun snapshot(directory: Path): Map<String, List<Byte>> =
        RecoveryStore.FILE_NAMES.associateWith { fileName ->
            directory.resolve(fileName).readBytes().toList()
        }

    private data object SimulatedPowerLoss : Error()

    private companion object {
        val PASSPHRASE = "correct horse battery staple".toCharArray()
    }
}
