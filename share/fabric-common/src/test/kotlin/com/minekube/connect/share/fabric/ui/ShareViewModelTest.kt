package com.minekube.connect.share.fabric.ui

import arrow.core.Either
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.ShareState
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AuthSource
import com.minekube.connect.share.admission.PendingAdmission
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.CredentialValidationError
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    @Test
    fun `start is disabled without a world and while a share is starting`() = runTest {
        val shareState = MutableStateFlow<ShareState>(ShareState.Idle)
        val viewModel = viewModel(
            shareState = shareState,
            worldAvailable = false,
        )
        advanceUntilIdle()

        assertFalse(viewModel.state.value.startEnabled)

        viewModel.setWorldAvailable(true)
        shareState.value = ShareState.Starting
        runCurrent()

        assertFalse(viewModel.state.value.startEnabled)

        shareState.value = ShareState.Idle
        runCurrent()

        assertTrue(viewModel.state.value.startEnabled)
    }

    @Test
    fun `capacity is clamped to supported guest range`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setMaxGuests(-20)
        assertEquals(ShareOptions.MIN_GUESTS, viewModel.state.value.options.maxGuests)

        viewModel.setMaxGuests(200)
        assertEquals(ShareOptions.MAX_GUESTS, viewModel.state.value.options.maxGuests)
    }

    @Test
    fun `successful import clears token from mutable UI state`() = runTest {
        val identityActions = FakeIdentityActions(
            current = localIdentity(),
            imported = localIdentity(endpoint = "friends"),
        )
        val viewModel = viewModel(identityActions = identityActions)
        advanceUntilIdle()

        viewModel.setImportEndpoint("friends")
        viewModel.setImportToken("super-secret-token")
        viewModel.importIdentity()
        advanceUntilIdle()

        assertEquals("friends", viewModel.state.value.identity?.endpoint)
        assertEquals("", viewModel.state.value.importDraft.token)
        assertEquals("super-secret-token", identityActions.lastImportedToken)
        assertFalse(viewModel.state.value.toString().contains("super-secret-token"))
    }

    @Test
    fun `environment managed identity fields cannot be edited`() = runTest {
        val identityActions = FakeIdentityActions(
            current = EndpointIdentitySummary(
                endpoint = "managed",
                endpointSource = CredentialSource.ENVIRONMENT,
                tokenSource = CredentialSource.ENVIRONMENT,
            ),
        )
        val viewModel = viewModel(identityActions = identityActions)
        advanceUntilIdle()

        viewModel.setImportEndpoint("changed")
        viewModel.setImportToken("changed-token")
        viewModel.importIdentity()
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.importDraft.endpoint)
        assertEquals("", viewModel.state.value.importDraft.token)
        assertFalse(viewModel.state.value.importDraft.endpointEditable)
        assertFalse(viewModel.state.value.importDraft.tokenEditable)
        assertEquals(0, identityActions.importCalls)
        assertEquals(
            "Connect credentials are managed by the environment",
            viewModel.state.value.safeMessage,
        )
    }

    @Test
    fun `allow and deny answer the exact pending request`() = runTest {
        val first = pending("Alice")
        val second = pending("Bob")
        val answers = mutableListOf<Pair<UUID, Boolean>>()
        val viewModel = viewModel(
            pending = MutableStateFlow(listOf(first, second)),
            answerAdmission = { requestId, allow ->
                answers += requestId to allow
            },
        )
        advanceUntilIdle()

        viewModel.allow(second.requestId)
        viewModel.deny(first.requestId)

        assertEquals(
            listOf(
                second.requestId to true,
                first.requestId to false,
            ),
            answers,
        )
    }

    private fun TestScope.viewModel(
        shareState: MutableStateFlow<ShareState> =
            MutableStateFlow(ShareState.Idle),
        pending: MutableStateFlow<List<PendingAdmission>> =
            MutableStateFlow(emptyList()),
        worldAvailable: Boolean = true,
        identityActions: EndpointIdentityUiActions =
            FakeIdentityActions(localIdentity()),
        answerAdmission: (UUID, Boolean) -> Unit = { _, _ -> },
    ) = ShareViewModel(
        scope = backgroundScope,
        shareState = shareState,
        pendingAdmissions = pending,
        initialWorldAvailable = worldAvailable,
        identityActions = identityActions,
        startShare = { options ->
            Either.Right(
                ShareState.Sharing(
                    endpoint = "share",
                    address = "${options.maxGuests}.example.test",
                ),
            )
        },
        stopShare = { Either.Right(Unit) },
        answerAdmission = answerAdmission,
    )

    private fun pending(name: String) = PendingAdmission(
        requestId = UUID.randomUUID(),
        identity = AdmissionIdentity.Authenticated(
            name = name,
            uuid = UUID.randomUUID(),
            source = AuthSource.CONNECT,
        ),
    )

    private fun localIdentity(endpoint: String = "generated") =
        EndpointIdentitySummary(
            endpoint = endpoint,
            endpointSource = CredentialSource.GENERATED,
            tokenSource = CredentialSource.GENERATED,
        )

    private class FakeIdentityActions(
        private val current: EndpointIdentitySummary,
        private val imported: EndpointIdentitySummary = current,
    ) : EndpointIdentityUiActions {
        var importCalls = 0
        var lastImportedToken: String? = null

        override suspend fun current(): EndpointIdentitySummary = current

        override suspend fun import(
            endpoint: String,
            token: String,
        ): Either<CredentialValidationError, EndpointIdentitySummary> {
            importCalls++
            lastImportedToken = token
            return Either.Right(imported)
        }

        override suspend fun importTokenFile(
            endpoint: String,
            tokenFile: Path,
        ): Either<CredentialValidationError, EndpointIdentitySummary> =
            Either.Right(imported)

        override suspend fun reset():
            Either<CredentialValidationError, EndpointIdentitySummary> =
            Either.Right(imported.copy(endpoint = "replacement"))
    }
}
