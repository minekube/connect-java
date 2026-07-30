package com.minekube.connect.share.fabric

import com.minekube.connect.share.ShareGameMode
import com.minekube.connect.share.ShareOptions
import com.minekube.connect.share.ShareState
import com.minekube.connect.share.fabric.ui.IdentityImportDraft
import com.minekube.connect.share.fabric.ui.ShareUiState
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.EndpointIdentity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SecretRedactionTest {
    @Test
    fun `identity and screen models redact entered endpoint tokens`() {
        val rawToken = "connect-secret-token"
        val identity = EndpointIdentity(
            endpoint = "friends",
            token = rawToken,
            endpointSource = CredentialSource.IMPORTED,
            tokenSource = CredentialSource.IMPORTED,
        )
        val screen = ShareUiState(
            worldAvailable = true,
            shareState = ShareState.Idle,
            options = ShareOptions(
                gameMode = ShareGameMode.SURVIVAL,
                allowCheats = false,
            ),
            pendingAdmissions = emptyList(),
            importDraft = IdentityImportDraft(
                endpoint = "friends",
                token = rawToken,
            ),
        )

        listOf(identity.toString(), screen.toString()).forEach { rendered ->
            assertContains(rendered, "<redacted>")
            assertFalse(rendered.contains(rawToken))
        }
    }
}
