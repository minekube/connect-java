package com.minekube.connect.share.recovery

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

data class RecoverySummary(
    val entryCount: Int,
    val includesPreferences: Boolean,
    val includesEndpointIdentity: Boolean,
)

sealed interface RecoveryStoreError {
    data class ArchiveFailure(
        val reason: RecoveryArchiveError,
    ) : RecoveryStoreError

    data object MissingRequiredMaterial : RecoveryStoreError
    data object UnsafeMaterial : RecoveryStoreError
    data object BackupReadFailed : RecoveryStoreError
    data object BackupWriteFailed : RecoveryStoreError
    data object ReplacementFailed : RecoveryStoreError
}

/**
 * Reads and replaces only Connect Share's explicitly recoverable files.
 *
 * Callers must stop the active Share runtime before importing. Every import is
 * staged and backed up before a durable marker permits the first replacement.
 */
class RecoveryStore(
    private val directory: Path,
    private val archive: RecoveryArchive = RecoveryArchive.production(),
    private val beforeReplace: (index: Int) -> Unit = {},
) {
    private val operationLock = Any()

    fun exportTo(
        target: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary> = synchronized(operationLock) {
        try {
            Files.createDirectories(directory)
            ensureSafeDirectory(directory)
            recoverInterruptedTransaction()
        } catch (_: IOException) {
            return@synchronized RecoveryStoreError.BackupWriteFailed.left()
        } catch (_: SecurityException) {
            return@synchronized RecoveryStoreError.UnsafeMaterial.left()
        }

        val entries = when (val loaded = loadEntries()) {
            is Either.Left -> return@synchronized loaded
            is Either.Right -> loaded.value
        }
        try {
            val encrypted = archive.encrypt(entries, passphrase)
            val failure = encrypted.leftOrNull()
            if (failure != null) {
                return@synchronized RecoveryStoreError.ArchiveFailure(failure).left()
            }
            val bytes = encrypted.getOrNull()!!
            try {
                writeBackupAtomically(target, bytes)
            } catch (_: IOException) {
                return@synchronized RecoveryStoreError.BackupWriteFailed.left()
            } catch (_: SecurityException) {
                return@synchronized RecoveryStoreError.BackupWriteFailed.left()
            } finally {
                bytes.fill(0)
            }
            summary(entries).right()
        } finally {
            entries.forEach { it.contents.fill(0) }
        }
    }

    fun importFrom(
        source: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary> = synchronized(operationLock) {
        try {
            Files.createDirectories(directory)
            ensureSafeDirectory(directory)
            recoverInterruptedTransaction()
        } catch (_: IOException) {
            return@synchronized RecoveryStoreError.ReplacementFailed.left()
        } catch (_: SecurityException) {
            return@synchronized RecoveryStoreError.UnsafeMaterial.left()
        }

        val encrypted = try {
            if (
                Files.isSymbolicLink(source) ||
                !Files.isRegularFile(source, NOFOLLOW_LINKS) ||
                Files.size(source) > RecoveryArchive.MAX_ARCHIVE_BYTES
            ) {
                return@synchronized RecoveryStoreError.BackupReadFailed.left()
            }
            Files.readAllBytes(source)
        } catch (_: IOException) {
            return@synchronized RecoveryStoreError.BackupReadFailed.left()
        } catch (_: SecurityException) {
            return@synchronized RecoveryStoreError.BackupReadFailed.left()
        }

        val entries = try {
            val decrypted = archive.decrypt(encrypted, passphrase)
            val failure = decrypted.leftOrNull()
            if (failure != null) {
                return@synchronized RecoveryStoreError.ArchiveFailure(failure).left()
            }
            decrypted.getOrNull()!!
        } finally {
            encrypted.fill(0)
        }

        try {
            replaceTransactionally(entries)
            summary(entries).right()
        } catch (failure: Exception) {
            try {
                recoverInterruptedTransaction()
            } catch (recoveryFailure: Exception) {
                failure.addSuppressed(recoveryFailure)
            }
            RecoveryStoreError.ReplacementFailed.left()
        } finally {
            entries.forEach { it.contents.fill(0) }
        }
    }

    private fun loadEntries(): Either<RecoveryStoreError, List<RecoveryEntry>> {
        val entries = mutableListOf<RecoveryEntry>()
        try {
            FILE_NAMES.forEach { fileName ->
                val file = directory.resolve(fileName)
                if (!Files.exists(file, NOFOLLOW_LINKS)) {
                    if (fileName in REQUIRED_FILE_NAMES) {
                        entries.forEach { it.contents.fill(0) }
                        return RecoveryStoreError.MissingRequiredMaterial.left()
                    }
                    return@forEach
                }
                if (
                    Files.isSymbolicLink(file) ||
                    !Files.isRegularFile(file, NOFOLLOW_LINKS)
                ) {
                    entries.forEach { it.contents.fill(0) }
                    return RecoveryStoreError.UnsafeMaterial.left()
                }
                if (Files.size(file) > RecoveryArchive.MAX_ENTRY_BYTES) {
                    entries.forEach { it.contents.fill(0) }
                    return RecoveryStoreError.UnsafeMaterial.left()
                }
                entries += RecoveryEntry(fileName, Files.readAllBytes(file))
            }
        } catch (_: IOException) {
            entries.forEach { it.contents.fill(0) }
            return RecoveryStoreError.BackupReadFailed.left()
        } catch (_: SecurityException) {
            entries.forEach { it.contents.fill(0) }
            return RecoveryStoreError.UnsafeMaterial.left()
        }
        return entries.right()
    }

    private fun replaceTransactionally(entries: List<RecoveryEntry>) {
        ensureSafeDirectory(directory)
        val transaction = directory.resolve(TRANSACTION_DIRECTORY)
        if (Files.exists(transaction, NOFOLLOW_LINKS)) {
            throw IOException("A recovery transaction already exists")
        }
        createOwnerOnlyDirectory(transaction)

        val imported = entries.associateBy(RecoveryEntry::fileName)
        val hadPrior = linkedMapOf<String, Boolean>()
        entries.forEach { entry ->
            writeDurable(transaction.resolve(stageName(entry.fileName)), entry.contents)
        }
        FILE_NAMES.forEach { fileName ->
            val target = directory.resolve(fileName)
            ensureSafeTarget(target)
            val exists = Files.exists(target, NOFOLLOW_LINKS)
            hadPrior[fileName] = exists
            if (exists) {
                copyDurable(target, transaction.resolve(backupName(fileName)))
            }
        }
        writeState(transaction, hadPrior, committed = false)

        FILE_NAMES.forEachIndexed { index, fileName ->
            beforeReplace(index + 1)
            val target = directory.resolve(fileName)
            val entry = imported[fileName]
            if (entry == null) {
                Files.deleteIfExists(target)
            } else {
                moveReplacing(transaction.resolve(stageName(fileName)), target)
                setOwnerOnlyFile(target)
                forceFile(target)
            }
        }
        writeState(transaction, hadPrior, committed = true)
        cleanupTransaction(transaction)
    }

    private fun recoverInterruptedTransaction() {
        val transaction = directory.resolve(TRANSACTION_DIRECTORY)
        if (!Files.exists(transaction, NOFOLLOW_LINKS)) {
            return
        }
        ensureSafeDirectory(transaction)
        val state = transaction.resolve(STATE_FILE)
        if (!Files.exists(state, NOFOLLOW_LINKS)) {
            cleanupTransaction(transaction)
            return
        }
        val transactionState = readState(state)
        if (!transactionState.committed) {
            FILE_NAMES.forEach { fileName ->
                val target = directory.resolve(fileName)
                if (transactionState.hadPrior.getValue(fileName)) {
                    val backup = transaction.resolve(backupName(fileName))
                    if (!Files.isRegularFile(backup, NOFOLLOW_LINKS)) {
                        throw IOException("Recovery backup is incomplete")
                    }
                    Files.copy(backup, target, REPLACE_EXISTING, COPY_ATTRIBUTES)
                    setOwnerOnlyFile(target)
                    forceFile(target)
                } else {
                    Files.deleteIfExists(target)
                }
            }
        }
        cleanupTransaction(transaction)
    }

    private fun writeBackupAtomically(target: Path, bytes: ByteArray) {
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IOException("Backup has no parent")
        Files.createDirectories(parent)
        if (Files.exists(absolute, NOFOLLOW_LINKS) && Files.isSymbolicLink(absolute)) {
            throw IOException("Backup target is unsafe")
        }
        val temporary = createOwnerOnlyTempFile(
            parent,
            absolute.fileName.toString() + ".",
            ".tmp",
        )
        try {
            writeDurable(temporary, bytes, create = false)
            moveReplacing(temporary, absolute)
            setOwnerOnlyFile(absolute)
            forceFile(absolute)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeState(
        transaction: Path,
        hadPrior: Map<String, Boolean>,
        committed: Boolean,
    ) {
        val content = buildString {
            append("version=1\n")
            append("committed=").append(committed).append('\n')
            FILE_NAMES.forEach { fileName ->
                append(fileName).append('=').append(hadPrior.getValue(fileName)).append('\n')
            }
        }.encodeToByteArray()
        val temporary = transaction.resolve(STATE_TEMP_FILE)
        try {
            Files.deleteIfExists(temporary)
            writeDurable(temporary, content)
            moveReplacing(temporary, transaction.resolve(STATE_FILE))
            forceFile(transaction.resolve(STATE_FILE))
        } finally {
            content.fill(0)
            Files.deleteIfExists(temporary)
        }
    }

    private fun readState(state: Path): TransactionState {
        if (Files.isSymbolicLink(state) || !Files.isRegularFile(state, NOFOLLOW_LINKS)) {
            throw IOException("Recovery transaction state is unsafe")
        }
        val values = Files.readAllLines(state).associate { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) {
                throw IOException("Recovery transaction state is invalid")
            }
            line.substring(0, separator) to line.substring(separator + 1)
        }
        if (values["version"] != "1") {
            throw IOException("Recovery transaction version is unsupported")
        }
        val committed = values["committed"]?.toBooleanStrictOrNull()
            ?: throw IOException("Recovery transaction state is invalid")
        val hadPrior = FILE_NAMES.associateWith { fileName ->
            values[fileName]?.toBooleanStrictOrNull()
                ?: throw IOException("Recovery transaction state is incomplete")
        }
        return TransactionState(committed, hadPrior)
    }

    private fun cleanupTransaction(transaction: Path) {
        FILE_NAMES.forEach { fileName ->
            Files.deleteIfExists(transaction.resolve(stageName(fileName)))
            Files.deleteIfExists(transaction.resolve(backupName(fileName)))
        }
        Files.deleteIfExists(transaction.resolve(STATE_FILE))
        Files.deleteIfExists(transaction.resolve(STATE_TEMP_FILE))
        Files.deleteIfExists(transaction)
    }

    private fun writeDurable(
        target: Path,
        bytes: ByteArray,
        create: Boolean = true,
    ) {
        val options = if (create) {
            arrayOf(CREATE_NEW, WRITE)
        } else {
            arrayOf(WRITE, TRUNCATE_EXISTING)
        }
        FileChannel.open(target, *options).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        setOwnerOnlyFile(target)
    }

    private fun copyDurable(source: Path, target: Path) {
        Files.copy(source, target, COPY_ATTRIBUTES)
        setOwnerOnlyFile(target)
        forceFile(target)
    }

    private fun forceFile(file: Path) {
        FileChannel.open(file, WRITE).use { it.force(true) }
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }

    private fun ensureSafeDirectory(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw IOException("Recovery directory is unsafe")
        }
    }

    private fun ensureSafeTarget(path: Path) {
        if (
            Files.exists(path, NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS))
        ) {
            throw IOException("Recovery target is unsafe")
        }
    }

    private fun setOwnerOnlyFile(path: Path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY_FILE)
        } catch (_: UnsupportedOperationException) {
            // POSIX permissions are not available on every supported platform.
        }
    }

    private fun createOwnerOnlyDirectory(path: Path) {
        try {
            Files.createDirectory(
                path,
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY),
            )
        } catch (_: UnsupportedOperationException) {
            Files.createDirectory(path)
        }
    }

    private fun createOwnerOnlyTempFile(
        directory: Path,
        prefix: String,
        suffix: String,
    ): Path = try {
        Files.createTempFile(
            directory,
            prefix,
            suffix,
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE),
        )
    } catch (_: UnsupportedOperationException) {
        Files.createTempFile(directory, prefix, suffix).also(::setOwnerOnlyFile)
    }

    private fun summary(entries: List<RecoveryEntry>) = RecoverySummary(
        entryCount = entries.size,
        includesPreferences = entries.any {
            it.fileName == RecoveryArchive.PREFERENCES_FILE
        },
        includesEndpointIdentity = entries.any {
            it.fileName == RecoveryArchive.ENDPOINT_CONFIG_FILE
        } && entries.any {
            it.fileName == RecoveryArchive.ENDPOINT_TOKEN_FILE
        },
    )

    private fun stageName(fileName: String) = "$fileName.new"

    private fun backupName(fileName: String) = "$fileName.bak"

    private data class TransactionState(
        val committed: Boolean,
        val hadPrior: Map<String, Boolean>,
    )

    companion object {
        const val TRANSACTION_DIRECTORY = ".connect-share-recovery-transaction"
        private const val STATE_FILE = "state"
        private const val STATE_TEMP_FILE = "state.new"

        val FILE_NAMES = listOf(
            RecoveryArchive.SOCIAL_IDENTITY_FILE,
            RecoveryArchive.GAMEPLAY_IDENTITY_FILE,
            RecoveryArchive.ACCESS_IDENTITY_FILE,
            RecoveryArchive.FRIENDS_FILE,
            RecoveryArchive.PREFERENCES_FILE,
            RecoveryArchive.ENDPOINT_CONFIG_FILE,
            RecoveryArchive.ENDPOINT_TOKEN_FILE,
        )
        private val REQUIRED_FILE_NAMES = setOf(
            RecoveryArchive.SOCIAL_IDENTITY_FILE,
            RecoveryArchive.GAMEPLAY_IDENTITY_FILE,
            RecoveryArchive.ACCESS_IDENTITY_FILE,
            RecoveryArchive.FRIENDS_FILE,
        )
        private val OWNER_ONLY_FILE = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val OWNER_ONLY_DIRECTORY = OWNER_ONLY_FILE +
            PosixFilePermission.OWNER_EXECUTE
    }
}
