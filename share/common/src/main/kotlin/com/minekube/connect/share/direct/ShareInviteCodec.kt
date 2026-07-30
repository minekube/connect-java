package com.minekube.connect.share.direct

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

class ShareInvitePayload(
    val wireVersion: Int,
    val shareId: UUID,
    val expiresAtEpochMillis: Long,
    val connectAddress: String?,
    val peerId: String,
    val internetDirectEnabled: Boolean,
    val directCandidates: List<String>,
    val capability: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ShareInvitePayload &&
            wireVersion == other.wireVersion &&
            shareId == other.shareId &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            connectAddress == other.connectAddress &&
            peerId == other.peerId &&
            internetDirectEnabled == other.internetDirectEnabled &&
            directCandidates == other.directCandidates &&
            capability == other.capability

    override fun hashCode(): Int {
        var result = wireVersion
        result = 31 * result + shareId.hashCode()
        result = 31 * result + expiresAtEpochMillis.hashCode()
        result = 31 * result + (connectAddress?.hashCode() ?: 0)
        result = 31 * result + peerId.hashCode()
        result = 31 * result + internetDirectEnabled.hashCode()
        result = 31 * result + directCandidates.hashCode()
        result = 31 * result + capability.hashCode()
        return result
    }

    override fun toString(): String =
        "ShareInvitePayload(wireVersion=$wireVersion, shareId=$shareId, " +
            "expiresAtEpochMillis=$expiresAtEpochMillis, " +
            "connectAddress=$connectAddress, peerId=$peerId, " +
            "internetDirectEnabled=$internetDirectEnabled, " +
            "directCandidates=<redacted>, capability=<redacted>)"
}

class SignedShareInvite(
    val payload: ShareInvitePayload,
    publicKey: ByteArray,
    signature: ByteArray,
) {
    val publicKey: ByteArray = publicKey.copyOf()
    val signature: ByteArray = signature.copyOf()

    override fun equals(other: Any?): Boolean =
        other is SignedShareInvite &&
            payload == other.payload &&
            publicKey.contentEquals(other.publicKey) &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int =
        31 * (31 * payload.hashCode() + publicKey.contentHashCode()) +
            signature.contentHashCode()

    override fun toString(): String =
        "SignedShareInvite(payload=$payload, publicKey=<redacted>, " +
            "signature=<redacted>)"
}

sealed interface ShareInviteError {
    val safeMessage: String

    data object Malformed : ShareInviteError {
        override val safeMessage = "This Connect Share invitation is invalid"
    }

    data class UnsupportedVersion(
        val version: Int,
    ) : ShareInviteError {
        override val safeMessage = "This Connect Share invitation uses an unsupported version"
    }

    data object Expired : ShareInviteError {
        override val safeMessage = "This Connect Share invitation has expired"
    }

    data object InvalidSignature : ShareInviteError {
        override val safeMessage = "This Connect Share invitation has an invalid signature"
    }

    data object RelayCandidateForbidden : ShareInviteError {
        override val safeMessage = "Direct Connect Share invitations cannot use a relay"
    }

    data object PeerMismatch : ShareInviteError {
        override val safeMessage =
            "A direct Connect Share route does not match the signed host"
    }
}

object ShareInviteCodec {
    const val WIRE_VERSION = 1
    private const val URI_PREFIX = "minekube://share/"
    private const val MAX_URI_LENGTH = 32_768
    private const val MAX_TEXT_LENGTH = 8_192
    private const val FIELD_COUNT = 10
    private const val UNSIGNED_FIELD_COUNT = 9

    fun encode(invite: SignedShareInvite): String {
        val writer = CborWriter()
        writer.array(FIELD_COUNT)
        writer.invitePayload(invite.payload)
        writer.bytes(invite.publicKey)
        writer.bytes(invite.signature)
        return URI_PREFIX + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(writer.toByteArray())
    }

    fun unsignedBytes(
        payload: ShareInvitePayload,
        publicKey: ByteArray,
    ): ByteArray = CborWriter().apply {
        array(UNSIGNED_FIELD_COUNT)
        invitePayload(payload)
        bytes(publicKey)
    }.toByteArray()

    fun decode(
        uri: String,
        now: Instant = Instant.now(),
    ): Either<ShareInviteError, SignedShareInvite> {
        if (!uri.startsWith(URI_PREFIX) || uri.length > MAX_URI_LENGTH) {
            return Either.Left(ShareInviteError.Malformed)
        }
        val parsed = try {
            val bytes = Base64.getUrlDecoder().decode(uri.removePrefix(URI_PREFIX))
            CborReader(bytes).readInvite()
        } catch (_: RuntimeException) {
            return Either.Left(ShareInviteError.Malformed)
        }
        return either {
            ensure(verify(parsed)) { ShareInviteError.InvalidSignature }
            ensure(parsed.payload.wireVersion == WIRE_VERSION) {
                ShareInviteError.UnsupportedVersion(parsed.payload.wireVersion)
            }
            ensure(parsed.payload.expiresAtEpochMillis >= now.toEpochMilli()) {
                ShareInviteError.Expired
            }
            ensure(parsed.payload.directCandidates.none(::isRelayAddress)) {
                ShareInviteError.RelayCandidateForbidden
            }
            ensure(
                parsed.payload.directCandidates.all {
                    candidatePeerId(it) == parsed.payload.peerId
                },
            ) {
                ShareInviteError.PeerMismatch
            }
            parsed
        }
    }

    private fun verify(invite: SignedShareInvite): Boolean = try {
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(invite.publicKey),
        )
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(unsignedBytes(invite.payload, invite.publicKey))
            verify(invite.signature)
        }
    } catch (_: Exception) {
        false
    }

    private fun isRelayAddress(candidate: String): Boolean =
        candidate.contains("/p2p-circuit") ||
            candidate.contains("/circuit/")

    private fun candidatePeerId(candidate: String): String? {
        val segments = candidate.split('/')
        val marker = segments.indexOfLast { it == "p2p" }
        if (marker < 0) return null
        return segments.getOrNull(marker + 1)?.takeIf(String::isNotBlank)
    }

    private fun CborWriter.invitePayload(payload: ShareInvitePayload) {
        unsigned(payload.wireVersion.toLong())
        text(payload.shareId.toString())
        unsigned(payload.expiresAtEpochMillis)
        nullableText(payload.connectAddress)
        text(payload.peerId)
        bool(payload.internetDirectEnabled)
        array(payload.directCandidates.size)
        payload.directCandidates.forEach(::text)
        text(payload.capability)
    }

    private class CborWriter {
        private val out = ByteArrayOutputStream()

        fun array(size: Int) = head(4, size.toLong())

        fun unsigned(value: Long) {
            require(value >= 0) { "CBOR value must be unsigned" }
            head(0, value)
        }

        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_TEXT_LENGTH) { "CBOR text is too long" }
            head(3, bytes.size.toLong())
            out.write(bytes)
        }

        fun nullableText(value: String?) {
            if (value == null) {
                out.write(0xf6)
            } else {
                text(value)
            }
        }

        fun bytes(value: ByteArray) {
            head(2, value.size.toLong())
            out.write(value)
        }

        fun bool(value: Boolean) {
            out.write(if (value) 0xf5 else 0xf4)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun head(major: Int, value: Long) {
            when {
                value < 24 -> out.write((major shl 5) or value.toInt())
                value <= 0xff -> {
                    out.write((major shl 5) or 24)
                    out.write(value.toInt())
                }

                value <= 0xffff -> {
                    out.write((major shl 5) or 25)
                    writeLong(value, 2)
                }

                value <= 0xffff_ffffL -> {
                    out.write((major shl 5) or 26)
                    writeLong(value, 4)
                }

                else -> {
                    out.write((major shl 5) or 27)
                    writeLong(value, 8)
                }
            }
        }

        private fun writeLong(value: Long, bytes: Int) {
            for (shift in (bytes - 1) * 8 downTo 0 step 8) {
                out.write((value ushr shift).toInt() and 0xff)
            }
        }
    }

    private class CborReader(
        private val bytes: ByteArray,
    ) {
        private var offset = 0

        fun readInvite(): SignedShareInvite {
            require(readLength(4) == FIELD_COUNT)
            val payload = ShareInvitePayload(
                wireVersion = unsigned().toInt(),
                shareId = UUID.fromString(text()),
                expiresAtEpochMillis = unsigned(),
                connectAddress = nullableText(),
                peerId = text(),
                internetDirectEnabled = bool(),
                directCandidates = List(readLength(4)) { text() },
                capability = text(),
            )
            val publicKey = byteString()
            val signature = byteString()
            require(offset == bytes.size)
            return SignedShareInvite(payload, publicKey, signature)
        }

        private fun unsigned(): Long = readValue(0)

        private fun text(): String {
            val length = readLength(3)
            require(length <= MAX_TEXT_LENGTH)
            return String(readBytes(length), Charsets.UTF_8)
        }

        private fun nullableText(): String? {
            if (peek() == 0xf6) {
                offset++
                return null
            }
            return text()
        }

        private fun byteString(): ByteArray = readBytes(readLength(2))

        private fun bool(): Boolean = when (readByte()) {
            0xf4 -> false
            0xf5 -> true
            else -> error("Expected CBOR boolean")
        }

        private fun readLength(expectedMajor: Int): Int {
            val value = readValue(expectedMajor)
            require(value <= Int.MAX_VALUE)
            return value.toInt()
        }

        private fun readValue(expectedMajor: Int): Long {
            val first = readByte()
            require(first ushr 5 == expectedMajor)
            return when (val additional = first and 0x1f) {
                in 0..23 -> additional.toLong()
                24 -> readLong(1)
                25 -> readLong(2)
                26 -> readLong(4)
                27 -> readLong(8)
                else -> error("Indefinite CBOR values are forbidden")
            }
        }

        private fun readLong(count: Int): Long {
            var value = 0L
            repeat(count) {
                value = (value shl 8) or readByte().toLong()
            }
            return value
        }

        private fun readBytes(count: Int): ByteArray {
            require(count >= 0 && offset + count <= bytes.size)
            return bytes.copyOfRange(offset, offset + count).also {
                offset += count
            }
        }

        private fun peek(): Int {
            require(offset < bytes.size)
            return bytes[offset].toInt() and 0xff
        }

        private fun readByte(): Int = peek().also { offset++ }
    }
}
