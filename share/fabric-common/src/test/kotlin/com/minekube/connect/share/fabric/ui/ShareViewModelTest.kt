package com.minekube.connect.share.fabric.ui

import arrow.core.Either
import com.minekube.connect.share.ShareLifecycleError
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.ShareState
import com.minekube.connect.share.admission.AdmissionIdentity
import com.minekube.connect.share.admission.AuthSource
import com.minekube.connect.share.admission.PendingAdmission
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.CredentialValidationError
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
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

    @Test
    fun `starting enables persistent friend sharing and stopping disables it`() = runTest {
        val persisted = mutableListOf<Boolean>()
        val viewModel = viewModel(
            persistShareWithFriends = persisted::add,
        )
        advanceUntilIdle()

        viewModel.start()
        advanceUntilIdle()
        viewModel.stop()
        advanceUntilIdle()

        assertEquals(listOf(true, false), persisted)
        assertFalse(viewModel.state.value.shareWithFriendsEnabled)
    }

    @Test
    fun `share operations are dispatched before invoking lifecycle work`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var starts = 0
        val viewModel = viewModel(
            scope = CoroutineScope(dispatcher),
            operationDispatcher = dispatcher,
            startShare = {
                starts++
                Either.Right(
                    ShareState.Sharing(
                        endpoint = "share",
                        address = "share.example.test",
                    ),
                )
            },
        )
        advanceUntilIdle()

        viewModel.start()

        assertEquals(0, starts)
        runCurrent()
        assertEquals(1, starts)
    }

    @Test
    fun `identity changes are rejected while a world share is active`() = runTest {
        val identityActions = FakeIdentityActions(
            current = localIdentity(),
            imported = localIdentity(endpoint = "replacement"),
        )
        val viewModel = viewModel(
            shareState = MutableStateFlow(
                ShareState.Sharing(
                    endpoint = "share",
                    address = "share.example.test",
                ),
            ),
            identityActions = identityActions,
        )
        advanceUntilIdle()

        viewModel.setImportEndpoint("replacement")
        viewModel.setImportToken("token")
        viewModel.importIdentity()
        advanceUntilIdle()

        assertEquals(0, identityActions.importCalls)
        assertEquals(
            "Stop sharing before changing Connect credentials",
            viewModel.state.value.safeMessage,
        )
    }

    @Test
    fun `enabled friend sharing resumes automatically in a new world`() = runTest {
        var starts = 0
        val viewModel = viewModel(
            worldAvailable = false,
            initialShareWithFriends = true,
            startShare = {
                starts++
                Either.Right(
                    ShareState.Sharing(
                        endpoint = "share",
                        address = "share.example.test",
                    ),
                )
            },
        )
        advanceUntilIdle()

        viewModel.setWorldAvailable(true)
        viewModel.resumeIfEnabled()

        assertEquals(1, starts)
        assertTrue(viewModel.state.value.shareWithFriendsEnabled)
    }

    private fun TestScope.viewModel(
        shareState: MutableStateFlow<ShareState> =
            MutableStateFlow(ShareState.Idle),
        pending: MutableStateFlow<List<PendingAdmission>> =
            MutableStateFlow(emptyList()),
        worldAvailable: Boolean = true,
        scope: CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        operationDispatcher: CoroutineDispatcher =
            StandardTestDispatcher(testScheduler),
        identityActions: EndpointIdentityUiActions =
            FakeIdentityActions(localIdentity()),
        answerAdmission: (UUID, Boolean) -> Unit = { _, _ -> },
        initialShareWithFriends: Boolean = false,
        persistShareWithFriends: (Boolean) -> Unit = {},
        startShare:
            suspend (ShareOptions) -> Either<ShareLifecycleError, ShareState.Sharing> =
            { options ->
                Either.Right(
                    ShareState.Sharing(
                        endpoint = "share",
                        address = "${options.maxGuests}.example.test",
                    ),
                )
            },
    ) = ShareViewModel(
        scope = scope,
        shareState = shareState,
        pendingAdmissions = pending,
        initialWorldAvailable = worldAvailable,
        identityActions = identityActions,
        initialShareWithFriendsEnabled = initialShareWithFriends,
        operationDispatcher = operationDispatcher,
        persistShareWithFriendsEnabled = persistShareWithFriends,
        startShare = startShare,
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
