package com.minekube.connect.share.recovery

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class RecoveryEntry(
    val fileName: String,
    val contents: ByteArray,
) {
    override fun toString(): String =
        "RecoveryEntry(fileName=$fileName, contents=<redacted>)"
}

sealed interface RecoveryArchiveError {
    data object WeakPassphrase : RecoveryArchiveError
    data object AuthenticationFailed : RecoveryArchiveError
    data object InvalidArchive : RecoveryArchiveError
    data object UnsupportedVersion : RecoveryArchiveError
    data object ArchiveTooLarge : RecoveryArchiveError
    data object EntryTooLarge : RecoveryArchiveError
    data object UnknownEntry : RecoveryArchiveError
    data object DuplicateEntry : RecoveryArchiveError
    data object MissingRequiredEntry : RecoveryArchiveError
    data object IncompleteEndpointIdentity : RecoveryArchiveError
}

/**
 * An offline, authenticated archive for Connect Share recovery material.
 *
 * The fixed-size envelope header is authenticated as AES-GCM additional data.
 * Archive contents and passphrases must never be logged or rendered.
 */
class RecoveryArchive private constructor(
    private val iterations: Int,
    private val secureRandom: SecureRandom,
) {
    fun encrypt(
        entries: List<RecoveryEntry>,
        passphrase: CharArray,
    ): Either<RecoveryArchiveError, ByteArray> {
        validatePassphrase(passphrase)?.let { return it.left() }
        validateEntries(entries)?.let { return it.left() }

        val plaintext = try {
            encodeManifest(entries)
        } catch (_: RuntimeException) {
            return RecoveryArchiveError.InvalidArchive.left()
        }
        if (plaintext.size > MAX_ARCHIVE_BYTES - HEADER_BYTES - GCM_TAG_BYTES) {
            plaintext.fill(0)
            return RecoveryArchiveError.ArchiveTooLarge.left()
        }

        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val header = header(iterations, salt, nonce)
        val key = deriveKey(passphrase, salt, iterations)
            ?: run {
                plaintext.fill(0)
                salt.fill(0)
                nonce.fill(0)
                return RecoveryArchiveError.InvalidArchive.left()
            }
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            val result = header + ciphertext
            if (result.size > MAX_ARCHIVE_BYTES) {
                RecoveryArchiveError.ArchiveTooLarge.left()
            } else {
                result.right()
            }
        } catch (_: RuntimeException) {
            RecoveryArchiveError.InvalidArchive.left()
        } catch (_: java.security.GeneralSecurityException) {
            RecoveryArchiveError.InvalidArchive.left()
        } finally {
            plaintext.fill(0)
            key.fill(0)
            salt.fill(0)
            nonce.fill(0)
        }
    }

    fun decrypt(
        archive: ByteArray,
        passphrase: CharArray,
    ): Either<RecoveryArchiveError, List<RecoveryEntry>> {
        validatePassphrase(passphrase)?.let { return it.left() }
        if (archive.size > MAX_ARCHIVE_BYTES) {
            return RecoveryArchiveError.ArchiveTooLarge.left()
        }
        if (archive.size < HEADER_BYTES + GCM_TAG_BYTES) {
            return RecoveryArchiveError.InvalidArchive.left()
        }

        val envelope = ByteBuffer.wrap(archive)
        val magic = ByteArray(MAGIC.size).also(envelope::get)
        if (!magic.contentEquals(MAGIC)) {
            return RecoveryArchiveError.InvalidArchive.left()
        }
        val version = envelope.get().toInt() and 0xff
        if (version != WIRE_VERSION) {
            return RecoveryArchiveError.UnsupportedVersion.left()
        }
        if (envelope.get() != KDF_ID || envelope.get() != CIPHER_ID) {
            return RecoveryArchiveError.UnsupportedVersion.left()
        }
        val archiveIterations = envelope.int
        if (archiveIterations !in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS) {
            return RecoveryArchiveError.InvalidArchive.left()
        }
        val salt = ByteArray(SALT_BYTES).also(envelope::get)
        val nonce = ByteArray(NONCE_BYTES).also(envelope::get)
        val header = archive.copyOfRange(0, HEADER_BYTES)
        val ciphertext = archive.copyOfRange(HEADER_BYTES, archive.size)
        val key = deriveKey(passphrase, salt, archiveIterations)
            ?: run {
                salt.fill(0)
                nonce.fill(0)
                ciphertext.fill(0)
                return RecoveryArchiveError.InvalidArchive.left()
            }

        val plaintext = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            return RecoveryArchiveError.AuthenticationFailed.left()
        } catch (_: RuntimeException) {
            return RecoveryArchiveError.InvalidArchive.left()
        } catch (_: java.security.GeneralSecurityException) {
            return RecoveryArchiveError.InvalidArchive.left()
        } finally {
            key.fill(0)
            salt.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }

        return try {
            decodeManifest(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray? {
        val specification = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM)
                .generateSecret(specification)
                .encoded
        } catch (_: java.security.GeneralSecurityException) {
            null
        } finally {
            specification.clearPassword()
        }
    }

    private fun encodeManifest(entries: List<RecoveryEntry>): ByteArray {
        val root = JsonObject().apply {
            addProperty("version", MANIFEST_VERSION)
            add("entries", JsonArray().apply {
                entries.forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("name", entry.fileName)
                        addProperty("length", entry.contents.size)
                        addProperty(
                            "content",
                            Base64.getEncoder().encodeToString(entry.contents),
                        )
                    })
                }
            })
        }
        return root.toString().encodeToByteArray()
    }

    private fun decodeManifest(
        plaintext: ByteArray,
    ): Either<RecoveryArchiveError, List<RecoveryEntry>> {
        val entries = try {
            val root = JsonParser.parseString(plaintext.decodeToString()).asJsonObject
            if (root.get("version")?.asInt != MANIFEST_VERSION) {
                return RecoveryArchiveError.UnsupportedVersion.left()
            }
            val encodedEntries = root.getAsJsonArray("entries")
                ?: return RecoveryArchiveError.InvalidArchive.left()
            encodedEntries.map { element ->
                val value = element.asJsonObject
                val fileName = value.get("name")?.asString
                    ?: return RecoveryArchiveError.InvalidArchive.left()
                val expectedLength = value.get("length")?.asInt
                    ?: return RecoveryArchiveError.InvalidArchive.left()
                val content = Base64.getDecoder().decode(
                    value.get("content")?.asString
                        ?: return RecoveryArchiveError.InvalidArchive.left(),
                )
                if (expectedLength != content.size) {
                    content.fill(0)
                    return RecoveryArchiveError.InvalidArchive.left()
                }
                RecoveryEntry(fileName, content)
            }
        } catch (_: RuntimeException) {
            return RecoveryArchiveError.InvalidArchive.left()
        }
        validateEntries(entries)?.let { failure ->
            entries.forEach { it.contents.fill(0) }
            return failure.left()
        }
        return entries.right()
    }

    private fun validatePassphrase(
        passphrase: CharArray,
    ): RecoveryArchiveError? =
        RecoveryArchiveError.WeakPassphrase.takeIf {
            passphrase.size < MIN_PASSPHRASE_CHARS
        }

    private fun validateEntries(
        entries: List<RecoveryEntry>,
    ): RecoveryArchiveError? {
        if (entries.any { it.fileName !in ALLOWED_FILES }) {
            return RecoveryArchiveError.UnknownEntry
        }
        if (entries.map(RecoveryEntry::fileName).distinct().size != entries.size) {
            return RecoveryArchiveError.DuplicateEntry
        }
        if (entries.any { it.contents.size > MAX_ENTRY_BYTES }) {
            return RecoveryArchiveError.EntryTooLarge
        }
        if (!entries.mapTo(mutableSetOf(), RecoveryEntry::fileName)
                .containsAll(REQUIRED_FILES)
        ) {
            return RecoveryArchiveError.MissingRequiredEntry
        }
        val names = entries.mapTo(mutableSetOf(), RecoveryEntry::fileName)
        if (
            (ENDPOINT_CONFIG_FILE in names) xor
            (ENDPOINT_TOKEN_FILE in names)
        ) {
            return RecoveryArchiveError.IncompleteEndpointIdentity
        }
        return null
    }

    private fun header(
        iterations: Int,
        salt: ByteArray,
        nonce: ByteArray,
    ): ByteArray = ByteBuffer.allocate(HEADER_BYTES)
        .put(MAGIC)
        .put(WIRE_VERSION.toByte())
        .put(KDF_ID)
        .put(CIPHER_ID)
        .putInt(iterations)
        .put(salt)
        .put(nonce)
        .array()

    companion object {
        const val SOCIAL_IDENTITY_FILE = "share-libp2p-social-identity.key"
        const val GAMEPLAY_IDENTITY_FILE = "share-libp2p-identity.key"
        const val ACCESS_IDENTITY_FILE = "share-access-identity.json"
        const val FRIENDS_FILE = "friends.json"
        const val PREFERENCES_FILE = "share-preferences.json"
        const val ENDPOINT_CONFIG_FILE = "config.json"
        const val ENDPOINT_TOKEN_FILE = "token.json"

        const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
        const val VERSION_OFFSET = 4

        private const val WIRE_VERSION = 1
        private const val MANIFEST_VERSION = 1
        private const val PRODUCTION_KDF_ITERATIONS = 600_000
        private const val MIN_KDF_ITERATIONS = 1
        private const val MAX_KDF_ITERATIONS = 2_000_000
        private const val MIN_PASSPHRASE_CHARS = 12
        private const val KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val KDF_ID: Byte = 1
        private const val CIPHER_ID: Byte = 1
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf('C'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 'B'.code.toByte())
        private val REQUIRED_FILES = setOf(
            SOCIAL_IDENTITY_FILE,
            GAMEPLAY_IDENTITY_FILE,
            ACCESS_IDENTITY_FILE,
            FRIENDS_FILE,
        )
        private val ALLOWED_FILES = REQUIRED_FILES + setOf(
            PREFERENCES_FILE,
            ENDPOINT_CONFIG_FILE,
            ENDPOINT_TOKEN_FILE,
        )
        private val HEADER_BYTES = MAGIC.size + 1 + 1 + 1 + Int.SIZE_BYTES +
            SALT_BYTES + NONCE_BYTES

        fun production(): RecoveryArchive = RecoveryArchive(
            iterations = PRODUCTION_KDF_ITERATIONS,
            secureRandom = SecureRandom(),
        )

        internal fun testing(iterations: Int): RecoveryArchive {
            require(iterations in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS)
            return RecoveryArchive(iterations, SecureRandom())
        }
    }
}
