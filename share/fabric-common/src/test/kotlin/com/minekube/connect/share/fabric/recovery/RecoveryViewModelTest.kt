package com.minekube.connect.share.fabric.recovery

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.recovery.RecoveryArchiveError
import com.minekube.connect.share.recovery.RecoveryStoreError
import com.minekube.connect.share.recovery.RecoverySummary
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelTest {
    @Test
    fun `export mismatch clears both secrets without starting work`() = runTest {
        val actions = FakeRecoveryActions()
        val viewModel = viewModel(actions)
        val passphrase = PASSPHRASE.copyOf()
        val confirmation = "different recovery secret".toCharArray()

        viewModel.export(BACKUP, passphrase, confirmation)
        runCurrent()

        assertTrue(passphrase.all { it == '\u0000' })
        assertTrue(confirmation.all { it == '\u0000' })
        assertEquals(0, actions.exports)
        assertEquals(
            "connect_share.recovery.error.secret_mismatch",
            viewModel.state.value.safeMessage?.translationKey,
        )
    }

    @Test
    fun `file work is nonblocking and reports a safe export summary`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val actions = FakeRecoveryActions(exportGate = gate)
        val viewModel = viewModel(actions)

        viewModel.export(BACKUP, PASSPHRASE.copyOf(), PASSPHRASE.copyOf())
        runCurrent()

        assertTrue(viewModel.state.value.operationInProgress)
        assertEquals(RecoveryPhase.IDLE, viewModel.state.value.phase)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.operationInProgress)
        assertEquals(RecoveryPhase.EXPORTED, viewModel.state.value.phase)
        assertEquals(SUMMARY, viewModel.state.value.summary)
        assertEquals(
            "connect_share.recovery.exported",
            viewModel.state.value.safeMessage?.translationKey,
        )
        assertTrue(actions.lastExportSecret!!.all { it == '\u0000' })
    }

    @Test
    fun `authenticated preview requires confirmation then clears retained secret`() = runTest {
        val actions = FakeRecoveryActions()
        val viewModel = viewModel(actions)
        val passphrase = PASSPHRASE.copyOf()

        viewModel.previewImport(BACKUP, passphrase)
        advanceUntilIdle()

        assertTrue(passphrase.all { it == '\u0000' })
        assertEquals(RecoveryPhase.IMPORT_PREVIEW, viewModel.state.value.phase)
        assertEquals(SUMMARY, viewModel.state.value.summary)
        assertTrue(viewModel.state.value.importConfirmationRequired)
        val retained = actions.lastPreviewSecret!!
        assertFalse(retained.all { it == '\u0000' })

        viewModel.confirmImport()
        advanceUntilIdle()

        assertEquals(1, actions.imports)
        assertTrue(retained.all { it == '\u0000' })
        assertEquals(RecoveryPhase.RESTORED, viewModel.state.value.phase)
        assertFalse(viewModel.state.value.importConfirmationRequired)
        assertEquals(
            "connect_share.recovery.restored_restart",
            viewModel.state.value.safeMessage?.translationKey,
        )
    }

    @Test
    fun `cancelled preview and closed screen clear the retained secret`() = runTest {
        val actions = FakeRecoveryActions()
        val viewModel = viewModel(actions)

        viewModel.previewImport(BACKUP, PASSPHRASE.copyOf())
        advanceUntilIdle()
        val cancelled = actions.lastPreviewSecret!!
        viewModel.cancelImport()

        assertTrue(cancelled.all { it == '\u0000' })
        assertEquals(RecoveryPhase.IDLE, viewModel.state.value.phase)

        viewModel.previewImport(BACKUP, PASSPHRASE.copyOf())
        advanceUntilIdle()
        val closed = actions.lastPreviewSecret!!
        viewModel.close()

        assertTrue(closed.all { it == '\u0000' })
        assertEquals(RecoveryPhase.IDLE, viewModel.state.value.phase)
    }

    @Test
    fun `wrong secret uses recovery wording distinct from dashboard import`() = runTest {
        val actions = FakeRecoveryActions(
            previewResult = RecoveryStoreError.ArchiveFailure(
                RecoveryArchiveError.AuthenticationFailed,
            ).left(),
        )
        val viewModel = viewModel(actions)

        viewModel.previewImport(BACKUP, PASSPHRASE.copyOf())
        advanceUntilIdle()

        val key = viewModel.state.value.safeMessage?.translationKey.orEmpty()
        assertEquals("connect_share.recovery.error.authentication", key)
        assertFalse(key.contains("identity"))
        assertTrue(actions.lastPreviewSecret!!.all { it == '\u0000' })
    }

    @Test
    fun `active sharing refuses restore and clears the retained secret`() = runTest {
        val actions = FakeRecoveryActions()
        val viewModel = RecoveryViewModel(
            scope = this,
            actions = actions,
            operationDispatcher = StandardTestDispatcher(testScheduler),
            restoreAllowed = { false },
        )

        viewModel.previewImport(BACKUP, PASSPHRASE.copyOf())
        advanceUntilIdle()
        val retained = actions.lastPreviewSecret!!
        viewModel.confirmImport()
        advanceUntilIdle()

        assertEquals(0, actions.imports)
        assertTrue(retained.all { it == '\u0000' })
        assertEquals(
            "connect_share.recovery.error.stop_sharing",
            viewModel.state.value.safeMessage?.translationKey,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.viewModel(
        actions: RecoveryUiActions,
    ) = RecoveryViewModel(
        scope = this,
        actions = actions,
        operationDispatcher = StandardTestDispatcher(testScheduler),
    )

    private class FakeRecoveryActions(
        private val exportGate: CompletableDeferred<Unit>? = null,
        private val previewResult:
            Either<RecoveryStoreError, RecoverySummary> = SUMMARY.right(),
    ) : RecoveryUiActions {
        var exports = 0
        var imports = 0
        var lastExportSecret: CharArray? = null
        var lastPreviewSecret: CharArray? = null

        override suspend fun export(
            target: Path,
            passphrase: CharArray,
        ): Either<RecoveryStoreError, RecoverySummary> {
            exports++
            lastExportSecret = passphrase
            exportGate?.await()
            return SUMMARY.right()
        }

        override suspend fun preview(
            source: Path,
            passphrase: CharArray,
        ): Either<RecoveryStoreError, RecoverySummary> {
            lastPreviewSecret = passphrase
            return previewResult
        }

        override suspend fun import(
            source: Path,
            passphrase: CharArray,
        ): Either<RecoveryStoreError, RecoverySummary> {
            imports++
            assertEquals(lastPreviewSecret, passphrase)
            return SUMMARY.right()
        }
    }

    private companion object {
        val BACKUP: Path = Path.of("friends.connect-share-backup")
        val PASSPHRASE = "correct horse battery staple".toCharArray()
        val SUMMARY = RecoverySummary(
            entryCount = 7,
            includesPreferences = true,
            includesEndpointIdentity = true,
        )
    }
}
