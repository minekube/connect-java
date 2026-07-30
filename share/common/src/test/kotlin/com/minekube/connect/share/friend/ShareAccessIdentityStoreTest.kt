package com.minekube.connect.share.friend

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.io.TempDir

class ShareAccessIdentityStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `one access identity survives world changes and reloads`() {
        val ids = values(FIRST_ID)
        val capabilities = values(FIRST_CAPABILITY)
        val store = store(ids, capabilities)

        val firstWorld = store.currentOrCreate()
        val secondWorld = store.currentOrCreate()
        val afterRestart = store(ids, capabilities).currentOrCreate()

        assertEquals(firstWorld, secondWorld)
        assertEquals(firstWorld, afterRestart)
        assertEquals(FIRST_ID, firstWorld.shareId)
        assertEquals(FIRST_CAPABILITY, firstWorld.capability)
    }

    @Test
    fun `rotation revokes the prior access identity`() {
        val store = store(
            values(FIRST_ID, SECOND_ID),
            values(FIRST_CAPABILITY, SECOND_CAPABILITY),
        )
        val original = store.currentOrCreate()

        val replacement = store.rotate()

        assertNotEquals(original.shareId, replacement.shareId)
        assertNotEquals(original.capability, replacement.capability)
        assertEquals(replacement, store.currentOrCreate())
    }

    @Test
    fun `rendering and persisted file do not expose capability through models`() {
        val identity = store(
            values(FIRST_ID),
            values(FIRST_CAPABILITY),
        ).currentOrCreate()

        val rendered = identity.toString()

        assertFalse(rendered.contains(FIRST_CAPABILITY))
        assertContains(rendered, "capability=<redacted>")
        assertContains(
            Files.readString(
                tempDir.resolve(ShareAccessIdentityStore.FILE_NAME),
            ),
            FIRST_CAPABILITY,
        )
    }

    private fun store(
        ids: () -> UUID,
        capabilities: () -> String,
    ) = ShareAccessIdentityStore.testing(
        directory = tempDir,
        generateShareId = ids,
        generateCapability = capabilities,
    )

    private fun <A> values(vararg values: A): () -> A {
        val remaining = ArrayDeque(values.toList())
        return { remaining.removeFirst() }
    }

    private companion object {
        val FIRST_ID: UUID =
            UUID.fromString("9e511188-31a9-43ac-9107-29d94410d554")
        val SECOND_ID: UUID =
            UUID.fromString("28c493d0-2bb0-4e2f-bacb-8af429073077")
        const val FIRST_CAPABILITY = "first-capability-123456789"
        const val SECOND_CAPABILITY = "second-capability-12345678"
    }
}
