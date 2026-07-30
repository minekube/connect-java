package com.minekube.connect.share.admission

import java.util.UUID

sealed interface AdmissionIdentity {
    val name: String
    val uuid: UUID

    data class Authenticated(
        override val name: String,
        override val uuid: UUID,
        val source: AuthSource,
    ) : AdmissionIdentity

    data class UnverifiedOffline(
        override val name: String,
        override val uuid: UUID,
        val connectionId: String,
        val ingress: Ingress,
    ) : AdmissionIdentity
}

enum class AuthSource {
    CONNECT,
    MOJANG,
}

enum class Ingress {
    CONNECT,
    DIRECT_LAN,
    DIRECT_INTERNET,
}

enum class AdmissionAnswer {
    ALLOW,
    DENY,
    TIMEOUT,
    STOPPED,
    CAPACITY,
}

data class PendingAdmission(
    val requestId: UUID,
    val identity: AdmissionIdentity,
)
