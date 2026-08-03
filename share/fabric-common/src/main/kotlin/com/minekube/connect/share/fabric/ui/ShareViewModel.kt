package com.minekube.connect.share.fabric.ui

import arrow.core.Either
import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareLifecycleError
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.ShareState
import com.minekube.connect.share.admission.PendingAdmission
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.CredentialValidationError
import com.minekube.connect.share.identity.EndpointCredentialValidator
import com.minekube.connect.share.identity.EndpointIdentity
import com.minekube.connect.share.identity.EndpointIdentityStore
import com.minekube.connect.share.friend.PresencePrivacy
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EndpointIdentitySummary(
    val endpoint: String,
    val endpointSource: CredentialSource,
    val tokenSource: CredentialSource,
) {
    val endpointManagedByEnvironment: Boolean =
        endpointSource == CredentialSource.ENVIRONMENT
    val tokenManagedByEnvironment: Boolean =
        tokenSource == CredentialSource.ENVIRONMENT
}

data class IdentityImportDraft(
    val endpoint: String = "",
    val token: String = "",
    val endpointEditable: Boolean = true,
    val tokenEditable: Boolean = true,
) {
    override fun toString(): String =
        "IdentityImportDraft(endpoint=$endpoint, token=<redacted>, " +
            "endpointEditable=$endpointEditable, tokenEditable=$tokenEditable)"
}

data class ShareUiState(
    val worldAvailable: Boolean,
    val shareState: ShareState,
    val options: ShareOptions,
    val pendingAdmissions: List<PendingAdmission>,
    val shareWithFriendsEnabled: Boolean = false,
    val presencePrivacy: PresencePrivacy = PresencePrivacy(),
    val identity: EndpointIdentitySummary? = null,
    val importDraft: IdentityImportDraft = IdentityImportDraft(),
    val operationInProgress: Boolean = false,
    val safeMessage: ShareUiMessage? = null,
) {
    val startEnabled: Boolean
        get() = worldAvailable &&
            shareState is ShareState.Idle &&
            !operationInProgress
}

interface EndpointIdentityUiActions {
    suspend fun current(): EndpointIdentitySummary

    suspend fun import(
        endpoint: String,
        token: String,
    ): Either<CredentialValidationError, EndpointIdentitySummary>

    suspend fun importTokenFile(
        endpoint: String,
        tokenFile: Path,
    ): Either<CredentialValidationError, EndpointIdentitySummary>

    suspend fun reset(): Either<CredentialValidationError, EndpointIdentitySummary>
}

class StoredEndpointIdentityUiActions(
    private val store: EndpointIdentityStore,
    private val validator: EndpointCredentialValidator,
) : EndpointIdentityUiActions {
    override suspend fun current(): EndpointIdentitySummary =
        store.currentOrCreate().redactedSummary()

    override suspend fun import(
        endpoint: String,
        token: String,
    ): Either<CredentialValidationError, EndpointIdentitySummary> =
        store.import(endpoint, token, validator).map(EndpointIdentity::redactedSummary)

    override suspend fun importTokenFile(
        endpoint: String,
        tokenFile: Path,
    ): Either<CredentialValidationError, EndpointIdentitySummary> =
        store.importTokenFile(endpoint, tokenFile, validator)
            .map(EndpointIdentity::redactedSummary)

    override suspend fun reset():
        Either<CredentialValidationError, EndpointIdentitySummary> =
        store.resetConfirmed().map(EndpointIdentity::redactedSummary)
}

class ShareViewModel(
    private val scope: CoroutineScope,
    shareState: StateFlow<ShareState>,
    pendingAdmissions: StateFlow<List<PendingAdmission>>,
    initialWorldAvailable: Boolean,
    private val identityActions: EndpointIdentityUiActions,
    initialShareWithFriendsEnabled: Boolean = false,
    private val persistShareWithFriendsEnabled: (Boolean) -> Unit = {},
    initialPresencePrivacy: PresencePrivacy = PresencePrivacy(),
    private val persistPresencePrivacy: (PresencePrivacy) -> Unit = {},
    private val startShare:
        suspend (ShareOptions) -> Either<ShareLifecycleError, ShareState.Sharing>,
    private val stopShare: suspend () -> Either<ShareLifecycleError, Unit>,
    private val answerAdmission: (UUID, Boolean) -> Unit,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onIdentityChanged: suspend () -> Unit = {},
    private val currentInvitation: () -> String? = { null },
) {
    private val operationMutex = Mutex()

    fun currentInvitation(): String? = currentInvitation.invoke()
    private val mutableState = MutableStateFlow(
        ShareUiState(
            worldAvailable = initialWorldAvailable,
            shareState = shareState.value,
            options = ShareOptions(
                gameMode = ShareGameMode.SURVIVAL,
                allowCheats = false,
            ),
            pendingAdmissions = pendingAdmissions.value,
            shareWithFriendsEnabled = initialShareWithFriendsEnabled,
            presencePrivacy = initialPresencePrivacy,
        ),
    )

    val state: StateFlow<ShareUiState> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            shareState.collectLatest { next ->
                update { copy(shareState = next) }
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pendingAdmissions.collectLatest { next ->
                update { copy(pendingAdmissions = next) }
            }
        }
        scope.launch(context = operationDispatcher) {
            runOperation {
                val identity = identityActions.current()
                update {
                    copy(
                        identity = identity,
                        importDraft = importDraft.withEditability(identity),
                    )
                }
            }
        }
    }

    fun setWorldAvailable(available: Boolean) {
        update { copy(worldAvailable = available) }
    }

    fun setGameMode(gameMode: ShareGameMode) {
        update { copy(options = options.copy(gameMode = gameMode)) }
    }

    fun setAllowCheats(allowCheats: Boolean) {
        update { copy(options = options.copy(allowCheats = allowCheats)) }
    }

    fun setMaxGuests(maxGuests: Int) {
        update {
            copy(
                options = options.copy(
                    maxGuests = maxGuests.coerceIn(
                        ShareOptions.MIN_GUESTS,
                        ShareOptions.MAX_GUESTS,
                    ),
                ),
            )
        }
    }

    fun setAllowInternetDirect(allowed: Boolean) {
        update {
            copy(options = options.copy(allowInternetDirect = allowed))
        }
    }

    fun setPresencePrivacy(privacy: PresencePrivacy) {
        update { copy(presencePrivacy = privacy) }
        scope.launch(context = operationDispatcher) {
            try {
                persistPresencePrivacy(privacy)
            } catch (_: Exception) {
                update { copy(safeMessage = PREFERENCES_FAILURE_MESSAGE) }
            }
        }
    }

    fun start() {
        if (!state.value.startEnabled) return
        scope.launch(context = operationDispatcher) {
            runOperation {
                if (!canStartCurrentWorld()) return@runOperation
                setShareWithFriendsEnabled(true)
                startCurrentWorld()
            }
        }
    }

    fun stop() {
        scope.launch(context = operationDispatcher) {
            runOperation {
                if (!canStopCurrentWorld()) return@runOperation
                try {
                    setShareWithFriendsEnabled(false)
                } finally {
                    stopCurrentWorld()
                }
            }
        }
    }

    suspend fun resumeIfEnabled() {
        if (
            !state.value.shareWithFriendsEnabled ||
            !state.value.startEnabled
        ) {
            return
        }
        kotlinx.coroutines.withContext(operationDispatcher) {
            runOperation {
                if (!canStartCurrentWorld()) return@runOperation
                startCurrentWorld()
            }
        }
    }

    fun allow(requestId: UUID) {
        answerAdmission(requestId, true)
    }

    fun deny(requestId: UUID) {
        answerAdmission(requestId, false)
    }

    fun setImportEndpoint(endpoint: String) {
        if (!identityChangesAllowed()) {
            rejectIdentityChange()
            return
        }
        update {
            if (!importDraft.endpointEditable) {
                this
            } else {
                copy(importDraft = importDraft.copy(endpoint = endpoint))
            }
        }
    }

    fun setImportToken(token: String) {
        if (!identityChangesAllowed()) {
            rejectIdentityChange()
            return
        }
        update {
            if (!importDraft.tokenEditable) {
                this
            } else {
                copy(importDraft = importDraft.copy(token = token))
            }
        }
    }

    fun importIdentity() {
        if (!identityChangesAllowed()) {
            rejectIdentityChange()
            return
        }
        val draft = state.value.importDraft
        if (!draft.endpointEditable || !draft.tokenEditable) {
            update { copy(safeMessage = MANAGED_MESSAGE) }
            return
        }
        scope.launch(context = operationDispatcher) {
            runOperation {
                applyIdentityResult(
                    identityActions.import(draft.endpoint, draft.token),
                )
            }
        }
    }

    fun importTokenFile(tokenFile: Path) {
        if (!identityChangesAllowed()) {
            rejectIdentityChange()
            return
        }
        val draft = state.value.importDraft
        if (!draft.endpointEditable || !draft.tokenEditable) {
            update { copy(safeMessage = MANAGED_MESSAGE) }
            return
        }
        scope.launch(context = operationDispatcher) {
            runOperation {
                applyIdentityResult(
                    identityActions.importTokenFile(draft.endpoint, tokenFile),
                )
            }
        }
    }

    fun resetIdentity() {
        if (!identityChangesAllowed()) {
            rejectIdentityChange()
            return
        }
        scope.launch(context = operationDispatcher) {
            runOperation {
                applyIdentityResult(identityActions.reset())
            }
        }
    }

    private suspend fun applyIdentityResult(
        result: Either<CredentialValidationError, EndpointIdentitySummary>,
    ) {
        result.fold(
            ifLeft = { failure ->
                update { copy(safeMessage = failure.uiMessage()) }
            },
            ifRight = { identity ->
                onIdentityChanged()
                update {
                    copy(
                        identity = identity,
                        importDraft = IdentityImportDraft()
                            .withEditability(identity),
                        safeMessage = null,
                    )
                }
            },
        )
    }

    private fun setShareWithFriendsEnabled(enabled: Boolean) {
        persistShareWithFriendsEnabled(enabled)
        update { copy(shareWithFriendsEnabled = enabled) }
    }

    private suspend fun startCurrentWorld() {
        startShare(state.value.options).fold(
            ifLeft = { failure ->
                update { copy(safeMessage = failure.uiMessage()) }
            },
            ifRight = {
                update {
                    copy(
                        shareState = it,
                        safeMessage = null,
                    )
                }
            },
        )
    }

    private suspend fun stopCurrentWorld() {
        stopShare().fold(
            ifLeft = { failure ->
                update { copy(safeMessage = failure.uiMessage()) }
            },
            ifRight = {
                update {
                    copy(
                        shareState = ShareState.Idle,
                        safeMessage = null,
                    )
                }
            },
        )
    }

    private suspend fun runOperation(operation: suspend () -> Unit) {
        operationMutex.withLock {
            update { copy(operationInProgress = true) }
            try {
                operation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                update { copy(safeMessage = GENERIC_FAILURE_MESSAGE) }
            } finally {
                update { copy(operationInProgress = false) }
            }
        }
    }

    private fun canStartCurrentWorld(): Boolean =
        state.value.worldAvailable && state.value.shareState is ShareState.Idle

    private fun canStopCurrentWorld(): Boolean = when (state.value.shareState) {
        ShareState.Idle -> false

        ShareState.Starting,
        is ShareState.Sharing,
        ShareState.Stopping,
        is ShareState.Failed,
        -> true
    }

    private fun update(transform: ShareUiState.() -> ShareUiState) {
        mutableState.value = mutableState.value.transform()
    }

    private fun identityChangesAllowed(): Boolean = when (
        state.value.shareState
    ) {
        ShareState.Idle,
        is ShareState.Failed,
        -> true

        ShareState.Starting,
        is ShareState.Sharing,
        ShareState.Stopping,
        -> false
    }

    private fun rejectIdentityChange() {
        update {
            copy(safeMessage = IDENTITY_ACTIVE_MESSAGE)
        }
    }

    private fun IdentityImportDraft.withEditability(
        identity: EndpointIdentitySummary,
    ): IdentityImportDraft = copy(
        endpointEditable = !identity.endpointManagedByEnvironment,
        tokenEditable = !identity.tokenManagedByEnvironment,
    )

    private companion object {
        val MANAGED_MESSAGE =
            ShareUiMessage("connect_share.error.identity_managed")
        val GENERIC_FAILURE_MESSAGE =
            ShareUiMessage("connect_share.error.generic")
        val IDENTITY_ACTIVE_MESSAGE =
            ShareUiMessage("connect_share.error.identity_active")
        val PREFERENCES_FAILURE_MESSAGE =
            ShareUiMessage("connect_share.error.preferences_save")
    }
}

private fun EndpointIdentity.redactedSummary() = EndpointIdentitySummary(
    endpoint = endpoint,
    endpointSource = endpointSource,
    tokenSource = tokenSource,
)
