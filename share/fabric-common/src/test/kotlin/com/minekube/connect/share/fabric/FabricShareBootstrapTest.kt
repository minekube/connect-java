package com.minekube.connect.share.fabric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FabricShareBootstrapTest {
    @Test
    fun `websocket watch URLs are normalized for OkHttp`() {
        assertEquals(
            "https://watch-connect.minekube.net/",
            FabricShareBootstrap.watchHttpUrl(emptyMap()).toString(),
        )
        assertEquals(
            "http://localhost:8080/watch",
            FabricShareBootstrap.watchHttpUrl(
                mapOf("CONNECT_WATCH_URL" to "ws://localhost:8080/watch"),
            ).toString(),
        )
    }

    @Test
    fun `social control hosting always exposes direct internet candidates`() {
        assertTrue(
            FabricShareBootstrap.socialControlOptions().allowInternetDirect,
        )
    }
}
