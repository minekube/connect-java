package com.minekube.connect.share.friend

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet
import java.util.UUID

data class ShareAccessIdentity(
    val shareId: UUID,
    val capability: String,
) {
    override fun toString(): String =
        "ShareAccessIdentity(shareId=$shareId, capability=<redacted>)"
}

class ShareAccessIdentityStore private constructor(
    private val directory: Path,
    private val generateShareId: () -> UUID,
    private val generateCapability: () -> String,
) {
    constructor(directory: Path) : this(
        directory = directory,
        generateShareId = UUID::randomUUID,
        generateCapability = ::newCapability,
    )

    @Synchronized
    fun currentOrCreate(): ShareAccessIdentity {
        Files.createDirectories(directory)
        return if (Files.exists(identityFile)) {
            read()
        } else {
            create().also(::write)
        }
    }

    @Synchronized
    fun rotate(): ShareAccessIdentity {
        Files.createDirectories(directory)
        return create().also(::write)
    }

    private fun create(): ShareAccessIdentity = ShareAccessIdentity(
        shareId = generateShareId(),
        capability = generateCapability().also {
            require(isValidCapability(it)) {
                "Generated friend capability is invalid"
            }
        },
    )

    private fun read(): ShareAccessIdentity {
        try {
            val json = GSON.fromJson(
                Files.readString(identityFile),
                JsonObject::class.java,
            ) ?: throw IOException("Share access identity is empty")
            val version = json.requiredInt("version")
            if (version != WIRE_VERSION) {
                throw IOException("Share access identity version is unsupported")
            }
            val shareId = try {
                UUID.fromString(json.requiredString("shareId"))
            } catch (exception: IllegalArgumentException) {
                throw IOException("Share access identity has an invalid ID", exception)
            }
            val capability = json.requiredString("capability")
            if (!isValidCapability(capability)) {
                throw IOException("Share access identity has an invalid capability")
            }
            return ShareAccessIdentity(shareId, capability)
        } catch (exception: JsonParseException) {
            throw IOException("Share access identity is invalid JSON", exception)
        } catch (exception: IllegalStateException) {
            throw IOException("Share access identity is invalid", exception)
        }
    }

    private fun write(identity: ShareAccessIdentity) {
        val json = JsonObject().apply {
            addProperty("version", WIRE_VERSION)
            addProperty("shareId", identity.shareId.toString())
            addProperty("capability", identity.capability)
        }
        val temporary = Files.createTempFile(
            directory,
            "$FILE_NAME.",
            ".tmp",
        )
        try {
            setOwnerOnlyPermissions(temporary)
            val bytes = GSON.toJson(json).toByteArray(StandardCharsets.UTF_8)
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                val remaining = ByteBuffer.wrap(bytes)
                while (remaining.hasRemaining()) {
                    channel.write(remaining)
                }
                channel.force(true)
            }
            try {
                Files.move(temporary, identityFile, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, identityFile, REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(identityFile)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun setOwnerOnlyPermissions(file: Path) {
        try {
            Files.setPosixFilePermissions(
                file,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows and other non-POSIX filesystems do not expose Unix modes.
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString
            ?: throw IOException("Share access identity is missing $name")

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt
            ?: throw IOException("Share access identity is missing $name")

    private val identityFile: Path
        get() = directory.resolve(FILE_NAME)

    companion object {
        const val FILE_NAME = "share-access-identity.json"
        private const val WIRE_VERSION = 1
        private const val CAPABILITY_BYTES = 32
        private val GSON = Gson()

        internal fun testing(
            directory: Path,
            generateShareId: () -> UUID,
            generateCapability: () -> String,
        ) = ShareAccessIdentityStore(
            directory = directory,
            generateShareId = generateShareId,
            generateCapability = generateCapability,
        )

        private fun newCapability(): String =
            ByteArray(CAPABILITY_BYTES)
                .also(SecureRandom()::nextBytes)
                .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

        private fun isValidCapability(value: String): Boolean =
            value.length >= 16 &&
                value.length <= 512 &&
                value.none(Char::isWhitespace)
    }
}
