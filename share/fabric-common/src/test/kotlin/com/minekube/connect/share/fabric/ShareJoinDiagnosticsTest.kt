package com.minekube.connect.share.fabric

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ShareJoinDiagnosticsTest {
    @Test
    fun `bundle is bounded stage-only and contains no connection secrets`() {
        val diagnostics = ShareJoinDiagnostics(
            now = { Instant.parse("2026-08-01T10:00:00Z") },
        )
        repeat(80) {
            diagnostics.record(
                JoinStage.DIRECT,
                if (it == 79) JoinOutcome.FAILED else JoinOutcome.STARTED,
            )
        }

        val bundle = diagnostics.bundle(
            minecraftVersion = "1.21.1",
            modVersion = "0.1.0",
        )

        assertContains(bundle, "Minecraft: 1.21.1")
        assertContains(bundle, "DIRECT: FAILED")
        assertFalse("/ip4/" in bundle)
        assertFalse("play.minekube.net" in bundle)
        assertEquals(50, bundle.lineSequence().count { ": DIRECT: " in it })
    }
}
