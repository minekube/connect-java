package com.minekube.connect.share.identity

import arrow.core.Either
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.minekube.connect.identity.EndpointTokenStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

class EndpointIdentityStore private constructor(
    private val directory: Path,
    private val environment: Map<String, String>,
    private val endpointNames: EndpointNameSource,
    private val tokenStore: EndpointTokenStore,
    private val generateToken: () -> String,
    private val beforeConfigReplace: () -> Unit,
) {
    constructor(
        directory: Path,
        environment: Map<String, String>,
        endpointNames: EndpointNameSource,
        tokenStore: EndpointTokenStore,
    ) : this(
        directory = directory,
        environment = environment,
        endpointNames = endpointNames,
        tokenStore = tokenStore,
        generateToken = tokenStore::generate,
        beforeConfigReplace = {},
    )

    suspend fun currentOrCreate(): EndpointIdentity {
        val stored = loadOrCreateStored()
        return applyEnvironment(stored)
    }

    suspend fun import(
        endpoint: String,
        token: String,
        validator: EndpointCredentialValidator,
    ): Either<CredentialValidationError, EndpointIdentity> = either {
        loadOrCreateStored()
        ensureCredentialsAreLocallyManaged()
        ensure(ENDPOINT_PATTERN.matches(endpoint)) {
            CredentialValidationError.InvalidInput("Endpoint name is invalid")
        }
        ensure(isValidToken(token)) {
            CredentialValidationError.InvalidInput("Connect token is invalid")
        }

        val candidate = EndpointIdentity(
            endpoint = endpoint,
            token = token,
            endpointSource = CredentialSource.IMPORTED,
            tokenSource = CredentialSource.IMPORTED,
        )
        validator.validate(candidate).bind()
        commit(candidate)
        candidate
    }

    suspend fun importTokenFile(
        endpoint: String,
        tokenFile: Path,
        validator: EndpointCredentialValidator,
    ): Either<CredentialValidationError, EndpointIdentity> = either {
        val token = readImportedToken(tokenFile).bind()
        import(endpoint, token, validator).bind()
    }

    suspend fun resetConfirmed(): Either<CredentialValidationError, EndpointIdentity> = either {
        val previous = loadOrCreateStored()
        ensureCredentialsAreLocallyManaged()

        val endpoint = nextEndpointDifferentFrom(previous.endpoint)
        val token = generateToken()
        ensure(isValidToken(token)) {
            CredentialValidationError.InvalidInput("Generated Connect token is invalid")
        }
        val replacement = EndpointIdentity(
            endpoint = endpoint,
            token = token,
            endpointSource = CredentialSource.GENERATED,
            tokenSource = CredentialSource.GENERATED,
        )
        commit(replacement)
        replacement
    }

    private suspend fun loadOrCreateStored(): EndpointIdentity {
        Files.createDirectories(directory)
        recoverInterruptedTransaction()

        val hasConfig = Files.exists(configFile)
        val hasToken = Files.exists(tokenFile)
        if (hasConfig != hasToken) {
            throw IOException(
                "Connect identity is incomplete; restore both config.json and token.json or reset it",
            )
        }
        if (hasConfig) {
            return readStoredIdentity()
        }

        val endpoint = endpointNames.create()
        require(ENDPOINT_PATTERN.matches(endpoint)) {
            "Generated endpoint name is invalid"
        }
        val token = generateToken()
        require(isValidToken(token)) {
            "Generated Connect token is invalid"
        }
        return EndpointIdentity(
            endpoint = endpoint,
            token = token,
            endpointSource = CredentialSource.GENERATED,
            tokenSource = CredentialSource.GENERATED,
        ).also(::commit)
    }

    private fun readStoredIdentity(): EndpointIdentity {
        val config = readConfig(configFile)
        val token = tokenStore.load(tokenFile, emptyMap()).orElseThrow {
            IOException("Connect token file does not contain a token")
        }
        return EndpointIdentity(
            endpoint = config.endpoint,
            token = token,
            endpointSource = config.endpointSource,
            tokenSource = config.tokenSource,
        )
    }

    private fun applyEnvironment(stored: EndpointIdentity): EndpointIdentity {
        val endpointOverride = environment[ENV_ENDPOINT]
        if (endpointOverride != null && !ENDPOINT_PATTERN.matches(endpointOverride)) {
            throw IllegalArgumentException("CONNECT_ENDPOINT is not a valid endpoint name")
        }
        val resolvedToken = tokenStore.load(tokenFile, environment).orElseThrow {
            IOException("Connect token file does not contain a token")
        }
        return stored.copy(
            endpoint = endpointOverride ?: stored.endpoint,
            token = resolvedToken,
            endpointSource = if (endpointOverride == null) {
                stored.endpointSource
            } else {
                CredentialSource.ENVIRONMENT
            },
            tokenSource = if (environment.containsKey(EndpointTokenStore.ENV_TOKEN)) {
                CredentialSource.ENVIRONMENT
            } else {
                stored.tokenSource
            },
        )
    }

    private fun arrow.core.raise.Raise<CredentialValidationError>
        .ensureCredentialsAreLocallyManaged() {
        val managedFields = buildList {
            if (environment.containsKey(ENV_ENDPOINT)) add(ENV_ENDPOINT)
            if (environment.containsKey(EndpointTokenStore.ENV_TOKEN)) {
                add(EndpointTokenStore.ENV_TOKEN)
            }
        }
        ensure(managedFields.isEmpty()) {
            CredentialValidationError.ManagedByEnvironment(
                fields = nonEmptyListOf(
                    managedFields.first(),
                    *managedFields.drop(1).toTypedArray(),
                ),
            )
        }
    }

    private fun readImportedToken(
        source: Path,
    ): Either<CredentialValidationError, String> = either {
        val loaded = try {
            tokenStore.load(source, emptyMap())
        } catch (_: IOException) {
            raise(CredentialValidationError.InvalidInput("Selected token file is invalid"))
        } catch (_: IllegalArgumentException) {
            raise(CredentialValidationError.InvalidInput("Selected token file is invalid"))
        }
        ensureNotNull(loaded.orElse(null)) {
            CredentialValidationError.InvalidInput("Selected token file has no token")
        }
    }

    private suspend fun nextEndpointDifferentFrom(previous: String): String {
        repeat(MAX_ENDPOINT_GENERATION_ATTEMPTS) {
            val candidate = endpointNames.create()
            require(ENDPOINT_PATTERN.matches(candidate)) {
                "Generated endpoint name is invalid"
            }
            if (candidate != previous) {
                return candidate
            }
        }
        throw IOException("Could not generate a replacement endpoint name")
    }

    private fun commit(identity: EndpointIdentity) {
        Files.createDirectories(directory)
        val previous = if (Files.exists(configFile) && Files.exists(tokenFile)) {
            readStoredIdentity()
        } else {
            null
        }
        val id = UUID.randomUUID().toString()
        val transaction = IdentityTransaction(
            oldEndpoint = previous?.endpoint,
            newEndpoint = identity.endpoint,
            tokenBackup = "token.json.$id.bak",
            configBackup = "config.json.$id.bak",
            tokenStage = "token.json.$id.new",
            configStage = "config.json.$id.new",
            hadToken = Files.exists(tokenFile),
            hadConfig = Files.exists(configFile),
            committed = false,
        )
        writeTransaction(transaction)

        try {
            if (transaction.hadToken) {
                copyDurable(tokenFile, resolveTransactionFile(transaction.tokenBackup))
            }
            if (transaction.hadConfig) {
                copyDurable(configFile, resolveTransactionFile(transaction.configBackup))
            }

            tokenStore.save(resolveTransactionFile(transaction.tokenStage), identity.token)
            writeAtomic(
                resolveTransactionFile(transaction.configStage),
                serializeConfig(identity),
            )
            moveReplacing(resolveTransactionFile(transaction.tokenStage), tokenFile)
            beforeConfigReplace()
            moveReplacing(resolveTransactionFile(transaction.configStage), configFile)
            forceFile(tokenFile)
            forceFile(configFile)

            writeTransaction(transaction.copy(committed = true))
            cleanupTransactionFiles(transaction)
            Files.deleteIfExists(transactionFile)
        } catch (failure: Throwable) {
            try {
                recoverInterruptedTransaction()
            } catch (recoveryFailure: Throwable) {
                failure.addSuppressed(recoveryFailure)
            }
            throw failure
        }
    }

    private fun recoverInterruptedTransaction() {
        if (!Files.exists(transactionFile)) {
            return
        }

        val transaction = readTransaction()
        if (transaction.committed) {
            cleanupTransactionFiles(transaction)
            Files.deleteIfExists(transactionFile)
            return
        }

        restoreOrRemove(
            target = tokenFile,
            backup = resolveTransactionFile(transaction.tokenBackup),
            hadPriorFile = transaction.hadToken,
        )
        restoreOrRemove(
            target = configFile,
            backup = resolveTransactionFile(transaction.configBackup),
            hadPriorFile = transaction.hadConfig,
        )
        cleanupTransactionFiles(transaction)
        Files.deleteIfExists(transactionFile)
    }

    private fun restoreOrRemove(target: Path, backup: Path, hadPriorFile: Boolean) {
        if (hadPriorFile) {
            if (Files.exists(backup)) {
                moveReplacing(backup, target)
            }
        } else {
            Files.deleteIfExists(target)
        }
    }

    private fun cleanupTransactionFiles(transaction: IdentityTransaction) {
        Files.deleteIfExists(resolveTransactionFile(transaction.tokenStage))
        Files.deleteIfExists(resolveTransactionFile(transaction.configStage))
        Files.deleteIfExists(resolveTransactionFile(transaction.tokenBackup))
        Files.deleteIfExists(resolveTransactionFile(transaction.configBackup))
    }

    private fun readConfig(file: Path): PersistedConfig {
        try {
            val json = GSON.fromJson(Files.readString(file), JsonObject::class.java)
                ?: throw IOException("Connect identity config is empty")
            val endpoint = json.requiredString("endpoint")
            if (!ENDPOINT_PATTERN.matches(endpoint)) {
                throw IOException("Connect identity config has an invalid endpoint")
            }
            return PersistedConfig(
                endpoint = endpoint,
                endpointSource = json.requiredCredentialSource("endpointSource"),
                tokenSource = json.requiredCredentialSource("tokenSource"),
            )
        } catch (exception: JsonParseException) {
            throw IOException("Connect identity config is invalid JSON", exception)
        } catch (exception: IllegalStateException) {
            throw IOException("Connect identity config is invalid", exception)
        } catch (exception: IllegalArgumentException) {
            throw IOException("Connect identity config has an invalid credential source", exception)
        }
    }

    private fun serializeConfig(identity: EndpointIdentity): String {
        val json = JsonObject()
        json.addProperty("endpoint", identity.endpoint)
        json.addProperty("endpointSource", identity.endpointSource.name)
        json.addProperty("tokenSource", identity.tokenSource.name)
        return GSON.toJson(json)
    }

    private fun writeTransaction(transaction: IdentityTransaction) {
        val json = JsonObject()
        transaction.oldEndpoint?.let { json.addProperty("oldEndpoint", it) }
        json.addProperty("newEndpoint", transaction.newEndpoint)
        json.addProperty("tokenBackup", transaction.tokenBackup)
        json.addProperty("configBackup", transaction.configBackup)
        json.addProperty("tokenStage", transaction.tokenStage)
        json.addProperty("configStage", transaction.configStage)
        json.addProperty("hadToken", transaction.hadToken)
        json.addProperty("hadConfig", transaction.hadConfig)
        json.addProperty("committed", transaction.committed)
        writeAtomic(transactionFile, GSON.toJson(json))
    }

    private fun readTransaction(): IdentityTransaction {
        try {
            val json = GSON.fromJson(Files.readString(transactionFile), JsonObject::class.java)
                ?: throw IOException("Connect identity transaction is empty")
            return IdentityTransaction(
                oldEndpoint = json.optionalString("oldEndpoint"),
                newEndpoint = json.requiredString("newEndpoint"),
                tokenBackup = json.requiredFileName("tokenBackup"),
                configBackup = json.requiredFileName("configBackup"),
                tokenStage = json.requiredFileName("tokenStage"),
                configStage = json.requiredFileName("configStage"),
                hadToken = json.requiredBoolean("hadToken"),
                hadConfig = json.requiredBoolean("hadConfig"),
                committed = json.get("committed")?.asBoolean ?: false,
            )
        } catch (exception: JsonParseException) {
            throw IOException("Connect identity transaction is invalid JSON", exception)
        } catch (exception: IllegalStateException) {
            throw IOException("Connect identity transaction is invalid", exception)
        }
    }

    private fun writeAtomic(target: Path, content: String) {
        val temporary = Files.createTempFile(directory, target.fileName.toString() + ".", ".tmp")
        try {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                var remaining = ByteBuffer.wrap(bytes)
                while (remaining.hasRemaining()) {
                    channel.write(remaining)
                }
                channel.force(true)
            }
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copyDurable(source: Path, target: Path) {
        val temporary = Files.createTempFile(directory, target.fileName.toString() + ".", ".tmp")
        try {
            Files.copy(source, temporary, REPLACE_EXISTING, COPY_ATTRIBUTES)
            forceFile(temporary)
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
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

    private fun resolveTransactionFile(name: String): Path {
        val candidate = Path.of(name)
        require(candidate.nameCount == 1 && candidate.fileName.toString() == name) {
            "Transaction file name must not escape the identity directory"
        }
        return directory.resolve(candidate)
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString
            ?: throw IOException("Connect identity document is missing $name")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
            ?: throw IOException("Connect identity document is missing $name")

    private fun JsonObject.requiredCredentialSource(name: String): CredentialSource =
        CredentialSource.valueOf(requiredString(name))

    private fun JsonObject.requiredFileName(name: String): String =
        requiredString(name).also(::resolveTransactionFile)

    private data class PersistedConfig(
        val endpoint: String,
        val endpointSource: CredentialSource,
        val tokenSource: CredentialSource,
    )

    private data class IdentityTransaction(
        val oldEndpoint: String?,
        val newEndpoint: String,
        val tokenBackup: String,
        val configBackup: String,
        val tokenStage: String,
        val configStage: String,
        val hadToken: Boolean,
        val hadConfig: Boolean,
        val committed: Boolean,
    )

    private val configFile: Path
        get() = directory.resolve(CONFIG_FILE_NAME)

    private val tokenFile: Path
        get() = directory.resolve(TOKEN_FILE_NAME)

    private val transactionFile: Path
        get() = directory.resolve(TRANSACTION_FILE_NAME)

    companion object {
        const val ENV_ENDPOINT = "CONNECT_ENDPOINT"
        const val CONFIG_FILE_NAME = "config.json"
        const val TOKEN_FILE_NAME = "token.json"
        const val TRANSACTION_FILE_NAME = "identity-transaction.json"

        private val GSON = Gson()
        private val ENDPOINT_PATTERN = Regex("^[a-z0-9][a-z0-9-]{2,62}$")
        private const val MAX_ENDPOINT_GENERATION_ATTEMPTS = 8

        internal fun testing(
            directory: Path,
            environment: Map<String, String>,
            endpointNames: EndpointNameSource,
            tokenStore: EndpointTokenStore,
            generateToken: () -> String,
            beforeConfigReplace: () -> Unit = {},
        ) = EndpointIdentityStore(
            directory = directory,
            environment = environment,
            endpointNames = endpointNames,
            tokenStore = tokenStore,
            generateToken = generateToken,
            beforeConfigReplace = beforeConfigReplace,
        )

        private fun isValidToken(token: String): Boolean =
            token.startsWith("T-") &&
                token.length > 2 &&
                token.none(Char::isWhitespace)
    }
}
