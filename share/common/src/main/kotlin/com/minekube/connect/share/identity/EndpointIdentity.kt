package com.minekube.connect.share.identity

import arrow.core.Either
import arrow.core.NonEmptyList

enum class CredentialSource {
    GENERATED,
    IMPORTED,
    ENVIRONMENT,
}

data class EndpointIdentity(
    val endpoint: String,
    val token: String,
    val endpointSource: CredentialSource,
    val tokenSource: CredentialSource,
) {
    override fun toString(): String =
        "EndpointIdentity(endpoint=$endpoint, token=<redacted>, " +
            "endpointSource=$endpointSource, tokenSource=$tokenSource)"
}

fun interface EndpointNameSource {
    suspend fun create(): String
}

fun interface EndpointCredentialValidator {
    suspend fun validate(
        identity: EndpointIdentity,
    ): Either<CredentialValidationError, Unit>
}

sealed interface CredentialValidationError {
    val safeMessage: String

    data class InvalidInput(
        override val safeMessage: String,
    ) : CredentialValidationError

    data class Rejected(
        override val safeMessage: String,
    ) : CredentialValidationError

    data class Network(
        override val safeMessage: String,
    ) : CredentialValidationError

    data class ManagedByEnvironment(
        val fields: NonEmptyList<String>,
        override val safeMessage: String = "Connect credentials are managed by the environment",
    ) : CredentialValidationError
}
