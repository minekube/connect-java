package com.minekube.connect.share.admission

import java.util.UUID

sealed interface AdmissionIdentity {
    val name: String
    val uuid: UUID
    val directPeerId: String?

    data class Authenticated(
        override val name: String,
        override val uuid: UUID,
        val source: AuthSource,
        val ingress: Ingress = Ingress.CONNECT,
        override val directPeerId: String? = null,
    ) : AdmissionIdentity

    data class UnverifiedOffline(
        override val name: String,
        override val uuid: UUID,
        val connectionId: String,
        val ingress: Ingress,
        override val directPeerId: String? = null,
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
