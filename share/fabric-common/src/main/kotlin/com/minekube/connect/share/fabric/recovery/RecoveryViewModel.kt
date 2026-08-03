package com.minekube.connect.share.fabric.recovery

import arrow.core.Either
import com.minekube.connect.share.fabric.ui.ShareUiMessage
import com.minekube.connect.share.recovery.RecoveryArchiveError
import com.minekube.connect.share.recovery.RecoveryStore
import com.minekube.connect.share.recovery.RecoveryStoreError
import com.minekube.connect.share.recovery.RecoverySummary
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RecoveryPhase {
    IDLE,
    EXPORTED,
    IMPORT_PREVIEW,
    RESTORED,
}

data class RecoveryUiState(
    val phase: RecoveryPhase = RecoveryPhase.IDLE,
    val operationInProgress: Boolean = false,
    val summary: RecoverySummary? = null,
    val importConfirmationRequired: Boolean = false,
    val safeMessage: ShareUiMessage? = null,
)

interface RecoveryUiActions {
    suspend fun export(
        target: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary>

    suspend fun preview(
        source: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary>

    suspend fun import(
        source: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary>
}

class StoredRecoveryUiActions(
    private val store: RecoveryStore,
) : RecoveryUiActions {
    override suspend fun export(
        target: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary> =
        store.exportTo(target, passphrase)

    override suspend fun preview(
        source: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary> =
        store.preview(source, passphrase)

    override suspend fun import(
        source: Path,
        passphrase: CharArray,
    ): Either<RecoveryStoreError, RecoverySummary> =
        store.importFrom(source, passphrase)
}

class RecoveryViewModel(
    private val scope: CoroutineScope,
    private val actions: RecoveryUiActions,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val restoreAllowed: () -> Boolean = { true },
) : AutoCloseable {
    private val working = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow(RecoveryUiState())
    @Volatile
    private var pendingImport: PendingImport? = null

    val state: StateFlow<RecoveryUiState> = mutableState.asStateFlow()

    fun export(
        target: Path,
        passphrase: CharArray,
        confirmation: CharArray,
    ) {
        if (!passphrase.contentEquals(confirmation)) {
            passphrase.fill('\u0000')
            confirmation.fill('\u0000')
            update {
                copy(
                    safeMessage = ShareUiMessage(
                        "connect_share.recovery.error.secret_mismatch",
                    ),
                )
            }
            return
        }
        val owned = passphrase.copyOf()
        passphrase.fill('\u0000')
        confirmation.fill('\u0000')
        if (!beginOperation()) {
            owned.fill('\u0000')
            return
        }
        scope.launch(operationDispatcher) {
            try {
                actions.export(target, owned).fold(
                    ifLeft = { failure -> showFailure(failure) },
                    ifRight = { summary ->
                        update {
                            copy(
                                phase = RecoveryPhase.EXPORTED,
                                summary = summary,
                                safeMessage = ShareUiMessage(
                                    "connect_share.recovery.exported",
                                ),
                            )
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(RecoveryStoreError.BackupWriteFailed)
            } finally {
                owned.fill('\u0000')
                endOperation()
            }
        }
    }

    fun previewImport(source: Path, passphrase: CharArray) {
        val owned = passphrase.copyOf()
        passphrase.fill('\u0000')
        clearPendingImport()
        val operationGeneration = generation.incrementAndGet()
        if (!beginOperation()) {
            owned.fill('\u0000')
            return
        }
        scope.launch(operationDispatcher) {
            var retained = false
            try {
                actions.preview(source, owned).fold(
                    ifLeft = { failure -> showFailure(failure) },
                    ifRight = { summary ->
                        if (generation.get() == operationGeneration) {
                            pendingImport = PendingImport(source, owned)
                            retained = true
                            update {
                                copy(
                                    phase = RecoveryPhase.IMPORT_PREVIEW,
                                    summary = summary,
                                    importConfirmationRequired = true,
                                    safeMessage = null,
                                )
                            }
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(RecoveryStoreError.BackupReadFailed)
            } finally {
                if (!retained) {
                    owned.fill('\u0000')
                }
                endOperation()
            }
        }
    }

    fun confirmImport() {
        val pending = pendingImport ?: return
        if (working.get()) {
            return
        }
        if (!restoreAllowed()) {
            clearPendingImport()
            update {
                copy(
                    phase = RecoveryPhase.IDLE,
                    summary = null,
                    safeMessage = ShareUiMessage(
                        "connect_share.recovery.error.stop_sharing",
                    ),
                )
            }
            return
        }
        if (!beginOperation()) {
            return
        }
        if (pendingImport !== pending) {
            endOperation()
            return
        }
        pendingImport = null
        update { copy(importConfirmationRequired = false) }
        scope.launch(operationDispatcher) {
            try {
                actions.import(pending.source, pending.passphrase).fold(
                    ifLeft = { failure -> showFailure(failure) },
                    ifRight = { summary ->
                        update {
                            copy(
                                phase = RecoveryPhase.RESTORED,
                                summary = summary,
                                safeMessage = ShareUiMessage(
                                    "connect_share.recovery.restored_restart",
                                ),
                            )
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(RecoveryStoreError.ReplacementFailed)
            } finally {
                pending.passphrase.fill('\u0000')
                endOperation()
            }
        }
    }

    fun cancelImport() {
        generation.incrementAndGet()
        clearPendingImport()
        if (!working.get()) {
            mutableState.value = RecoveryUiState()
        }
    }

    fun clearMessage() {
        update { copy(safeMessage = null) }
    }

    override fun close() {
        cancelImport()
    }

    private fun beginOperation(): Boolean {
        if (!working.compareAndSet(false, true)) {
            return false
        }
        update {
            copy(
                operationInProgress = true,
                safeMessage = null,
            )
        }
        return true
    }

    private fun endOperation() {
        working.set(false)
        update { copy(operationInProgress = false) }
    }

    private fun clearPendingImport() {
        pendingImport?.passphrase?.fill('\u0000')
        pendingImport = null
        update { copy(importConfirmationRequired = false) }
    }

    private fun showFailure(failure: RecoveryStoreError) {
        update {
            copy(
                phase = RecoveryPhase.IDLE,
                summary = null,
                importConfirmationRequired = false,
                safeMessage = failure.uiMessage(),
            )
        }
    }

    private fun update(transform: RecoveryUiState.() -> RecoveryUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private data class PendingImport(
        val source: Path,
        val passphrase: CharArray,
    )
}

fun RecoveryStoreError.uiMessage(): ShareUiMessage = when (this) {
    is RecoveryStoreError.ArchiveFailure -> when (reason) {
        RecoveryArchiveError.WeakPassphrase ->
            ShareUiMessage("connect_share.recovery.error.weak_secret")
        RecoveryArchiveError.AuthenticationFailed ->
            ShareUiMessage("connect_share.recovery.error.authentication")
        RecoveryArchiveError.UnsupportedVersion ->
            ShareUiMessage("connect_share.recovery.error.unsupported")
        RecoveryArchiveError.ArchiveTooLarge,
        RecoveryArchiveError.EntryTooLarge,
        -> ShareUiMessage("connect_share.recovery.error.too_large")
        RecoveryArchiveError.InvalidArchive,
        RecoveryArchiveError.UnknownEntry,
        RecoveryArchiveError.DuplicateEntry,
        RecoveryArchiveError.MissingRequiredEntry,
        RecoveryArchiveError.IncompleteEndpointIdentity,
        -> ShareUiMessage("connect_share.recovery.error.invalid")
    }
    RecoveryStoreError.MissingRequiredMaterial ->
        ShareUiMessage("connect_share.recovery.error.nothing_to_export")
    RecoveryStoreError.UnsafeMaterial ->
        ShareUiMessage("connect_share.recovery.error.unsafe_files")
    RecoveryStoreError.BackupReadFailed ->
        ShareUiMessage("connect_share.recovery.error.read")
    RecoveryStoreError.BackupWriteFailed ->
        ShareUiMessage("connect_share.recovery.error.write")
    RecoveryStoreError.ReplacementFailed ->
        ShareUiMessage("connect_share.recovery.error.restore")
}
