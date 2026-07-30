package com.minekube.connect.share.identity

import arrow.core.Either
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.minekube.connect.identity.EndpointTokenStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class EndpointIdentityStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private val tokenStore = EndpointTokenStore()

    @Test
    fun `one generated identity survives reload and world changes`() = runTest {
        val endpoints = values("amber-fox")
        val tokens = values("T-AAAAAAAAAAAAAAAAAAAA")
        val store = store(endpoints, tokens)

        val firstWorld = store.currentOrCreate()
        val secondWorld = store.currentOrCreate()
        val afterRestart = store(endpoints, tokens).currentOrCreate()

        assertEquals(firstWorld, secondWorld)
        assertEquals(firstWorld, afterRestart)
        assertEquals(CredentialSource.GENERATED, firstWorld.endpointSource)
        assertEquals(CredentialSource.GENERATED, firstWorld.tokenSource)
    }

    @Test
    fun `environment overrides are resolved per field`() = runTest {
        val endpoints = values("amber-fox")
        val tokens = values("T-AAAAAAAAAAAAAAAAAAAA")
        val persisted = store(endpoints, tokens).currentOrCreate()

        val endpointManaged = store(
            endpoints,
            tokens,
            environment = mapOf(EndpointIdentityStore.ENV_ENDPOINT to "managed-endpoint"),
        ).currentOrCreate()
        val tokenManaged = store(
            endpoints,
            tokens,
            environment = mapOf(EndpointTokenStore.ENV_TOKEN to "T-managed-token"),
        ).currentOrCreate()

        assertEquals("managed-endpoint", endpointManaged.endpoint)
        assertEquals(persisted.token, endpointManaged.token)
        assertEquals(CredentialSource.ENVIRONMENT, endpointManaged.endpointSource)
        assertEquals(CredentialSource.GENERATED, endpointManaged.tokenSource)

        assertEquals(persisted.endpoint, tokenManaged.endpoint)
        assertEquals("T-managed-token", tokenManaged.token)
        assertEquals(CredentialSource.GENERATED, tokenManaged.endpointSource)
        assertEquals(CredentialSource.ENVIRONMENT, tokenManaged.tokenSource)
    }

    @Test
    fun `environment-managed credentials cannot be imported or reset`() = runTest {
        val endpoints = values("amber-fox")
        val tokens = values("T-AAAAAAAAAAAAAAAAAAAA")
        store(endpoints, tokens).currentOrCreate()
        val managed = store(
            endpoints,
            tokens,
            environment = mapOf(EndpointIdentityStore.ENV_ENDPOINT to "managed-endpoint"),
        )
        val before = snapshot()

        val imported = managed.import(
            "dashboard-endpoint",
            "T-BBBBBBBBBBBBBBBBBBBB",
            validValidator,
        )
        val reset = managed.resetConfirmed()

        assertIs<CredentialValidationError.ManagedByEnvironment>(
            assertIs<Either.Left<CredentialValidationError>>(imported).value,
        )
        assertIs<CredentialValidationError.ManagedByEnvironment>(
            assertIs<Either.Left<CredentialValidationError>>(reset).value,
        )
        assertSnapshotEquals(before)
    }

    @Test
    fun `dashboard import commits endpoint and token only after validation`() = runTest {
        val store = store(
            values("amber-fox"),
            values("T-AAAAAAAAAAAAAAAAAAAA"),
        )
        store.currentOrCreate()
        val before = snapshot()

        val result = store.import(
            endpoint = "dashboard-endpoint",
            token = "T-BBBBBBBBBBBBBBBBBBBB",
            validator = EndpointCredentialValidator { candidate ->
                assertEquals("dashboard-endpoint", candidate.endpoint)
                assertSnapshotEquals(before)
                Either.Right(Unit)
            },
        )

        val imported = assertIs<Either.Right<EndpointIdentity>>(result).value
        assertEquals("dashboard-endpoint", imported.endpoint)
        assertEquals("T-BBBBBBBBBBBBBBBBBBBB", imported.token)
        assertEquals(CredentialSource.IMPORTED, imported.endpointSource)
        assertEquals(CredentialSource.IMPORTED, imported.tokenSource)
        assertNotEquals(before.config.toList(), Files.readAllBytes(configFile()).toList())
        assertNotEquals(before.token.toList(), Files.readAllBytes(tokenFile()).toList())
    }

    @Test
    fun `bad token leaves prior identity byte-for-byte intact`() = runTest {
        val store = store(
            values("amber-fox"),
            values("T-AAAAAAAAAAAAAAAAAAAA"),
        )
        store.currentOrCreate()
        val before = snapshot()

        val result = store.import(
            "dashboard-endpoint",
            "not-a-connect-token",
            validValidator,
        )

        assertIs<Either.Left<CredentialValidationError>>(result)
        assertSnapshotEquals(before)
    }

    @Test
    fun `cancelled and failed validation leave prior identity intact`() = runTest {
        val store = store(
            values("amber-fox"),
            values("T-AAAAAAAAAAAAAAAAAAAA"),
        )
        store.currentOrCreate()
        val before = snapshot()

        val failed = store.import(
            "dashboard-endpoint",
            "T-BBBBBBBBBBBBBBBBBBBB",
            EndpointCredentialValidator {
                Either.Left(CredentialValidationError.Rejected("Endpoint credentials were rejected"))
            },
        )
        assertIs<Either.Left<CredentialValidationError>>(failed)
        assertSnapshotEquals(before)

        val thrown = runCatching {
            store.import(
                "dashboard-endpoint",
                "T-BBBBBBBBBBBBBBBBBBBB",
                EndpointCredentialValidator { throw CancellationException("screen closed") },
            )
        }.exceptionOrNull()
        assertIs<CancellationException>(thrown)
        assertSnapshotEquals(before)
    }

    @Test
    fun `plugin token json can be imported`() = runTest {
        val store = store(
            values("amber-fox"),
            values("T-AAAAAAAAAAAAAAAAAAAA"),
        )
        store.currentOrCreate()
        val pluginTokenFile = tempDir.resolve("existing-plugin").resolve("token.json")
        tokenStore.save(pluginTokenFile, "T-PLUGINPLUGINPLUGIN12")

        val result = store.importTokenFile(
            endpoint = "plugin-endpoint",
            tokenFile = pluginTokenFile,
            validator = validValidator,
        )

        val imported = assertIs<Either.Right<EndpointIdentity>>(result).value
        assertEquals("plugin-endpoint", imported.endpoint)
        assertEquals("T-PLUGINPLUGINPLUGIN12", imported.token)
        assertEquals(
            "T-PLUGINPLUGINPLUGIN12",
            tokenStore.load(tokenFile(), emptyMap()).orElseThrow(),
        )
    }

    @Test
    fun `reset is explicit and creates one replacement identity`() = runTest {
        val endpoints = values("amber-fox", "brisk-wolf")
        val tokens = values("T-AAAAAAAAAAAAAAAAAAAA", "T-BBBBBBBBBBBBBBBBBBBB")
        val store = store(endpoints, tokens)
        val original = store.currentOrCreate()

        val reset = assertIs<Either.Right<EndpointIdentity>>(store.resetConfirmed()).value
        val reloaded = store.currentOrCreate()

        assertNotEquals(original, reset)
        assertEquals("brisk-wolf", reset.endpoint)
        assertEquals("T-BBBBBBBBBBBBBBBBBBBB", reset.token)
        assertEquals(reset, reloaded)
    }

    @Test
    fun `logs and toString never contain token`() = runTest {
        val identity = store(
            values("amber-fox"),
            values("T-AAAAAAAAAAAAAAAAAAAA"),
        ).currentOrCreate()

        val rendered = identity.toString()

        assertFalse(rendered.contains(identity.token))
        assertContains(rendered, "token=<redacted>")
    }

    @Test
    fun `second file failure restores the prior identity`() = runTest {
        var configReplacements = 0
        val store = store(
            values("amber-fox"),
            values("T-AAAAAAAAAAAAAAAAAAAA"),
            beforeConfigReplace = {
                if (configReplacements++ > 0) {
                    error("injected config move failure")
                }
            },
        )
        store.currentOrCreate()
        val before = snapshot()

        val thrown = runCatching {
            store.import(
                "dashboard-endpoint",
                "T-BBBBBBBBBBBBBBBBBBBB",
                validValidator,
            )
        }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        assertSnapshotEquals(before)
        assertFalse(Files.exists(transactionFile()))
    }

    @Test
    fun `interrupted transaction rolls back on next load`() = runTest {
        val endpoints = values("amber-fox")
        val tokens = values("T-AAAAAAAAAAAAAAAAAAAA")
        val store = store(endpoints, tokens)
        val original = store.currentOrCreate()
        val before = snapshot()

        val tokenBackup = tempDir.resolve("token.json.manual.bak")
        val configBackup = tempDir.resolve("config.json.manual.bak")
        val tokenStage = tempDir.resolve("token.json.manual.new")
        val configStage = tempDir.resolve("config.json.manual.new")
        Files.copy(tokenFile(), tokenBackup)
        Files.copy(configFile(), configBackup)
        tokenStore.save(tokenFile(), "T-BBBBBBBBBBBBBBBBBBBB")
        Files.writeString(
            configFile(),
            """{"endpoint":"dashboard-endpoint","endpointSource":"IMPORTED","tokenSource":"IMPORTED"}""",
        )
        Files.writeString(
            transactionFile(),
            Gson().toJson(
                mapOf(
                    "oldEndpoint" to "amber-fox",
                    "newEndpoint" to "dashboard-endpoint",
                    "tokenBackup" to tokenBackup.fileName.toString(),
                    "configBackup" to configBackup.fileName.toString(),
                    "tokenStage" to tokenStage.fileName.toString(),
                    "configStage" to configStage.fileName.toString(),
                    "hadToken" to true,
                    "hadConfig" to true,
                ),
            ),
        )

        val recovered = store(endpoints, tokens).currentOrCreate()

        assertEquals(original, recovered)
        assertSnapshotEquals(before)
        assertFalse(Files.exists(transactionFile()))
    }

    private fun store(
        endpoints: () -> String,
        tokens: () -> String,
        environment: Map<String, String> = emptyMap(),
        beforeConfigReplace: () -> Unit = {},
    ) = EndpointIdentityStore.testing(
        directory = tempDir,
        environment = environment,
        endpointNames = EndpointNameSource { endpoints() },
        tokenStore = tokenStore,
        generateToken = tokens,
        beforeConfigReplace = beforeConfigReplace,
    )

    private fun values(vararg values: String): () -> String {
        val remaining = ArrayDeque(values.toList())
        return { remaining.removeFirst() }
    }

    private fun snapshot() = Snapshot(
        config = Files.readAllBytes(configFile()),
        token = Files.readAllBytes(tokenFile()),
    )

    private fun assertSnapshotEquals(expected: Snapshot) {
        assertContentEquals(expected.config, Files.readAllBytes(configFile()))
        assertContentEquals(expected.token, Files.readAllBytes(tokenFile()))
    }

    private fun configFile() = tempDir.resolve(EndpointIdentityStore.CONFIG_FILE_NAME)

    private fun tokenFile() = tempDir.resolve(EndpointIdentityStore.TOKEN_FILE_NAME)

    private fun transactionFile() = tempDir.resolve(EndpointIdentityStore.TRANSACTION_FILE_NAME)

    private data class Snapshot(val config: ByteArray, val token: ByteArray)

    private companion object {
        val validValidator = EndpointCredentialValidator { Either.Right(Unit) }
    }
}
