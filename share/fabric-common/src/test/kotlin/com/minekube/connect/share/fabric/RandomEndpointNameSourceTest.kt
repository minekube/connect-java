package com.minekube.connect.share.fabric

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class RandomEndpointNameSourceTest {
    @Test
    fun `returns a valid remote endpoint`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("amber-fox"))
            server.start()
            val source = RandomEndpointNameSource(
                client = OkHttpClient(),
                url = server.url("/"),
                timeout = 2.seconds,
                random = Random(7),
            )

            assertEquals("amber-fox", source.create())
        }
    }

    @Test
    fun `invalid empty and non-200 responses use lowercase fallback`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("INVALID ENDPOINT"))
            server.enqueue(MockResponse().setBody(""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("secret response"))
            server.start()
            val source = RandomEndpointNameSource(
                client = OkHttpClient(),
                url = server.url("/"),
                timeout = 2.seconds,
                random = Random(7),
            )

            repeat(3) {
                assertTrue(source.create().matches(Regex("^[a-z]{5}$")))
            }
        }
    }

    @Test
    fun `timeout uses lowercase fallback`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("amber-fox")
                    .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS),
            )
            server.start()
            val source = RandomEndpointNameSource(
                client = OkHttpClient(),
                url = server.url("/"),
                timeout = 50.milliseconds,
                random = Random(7),
            )

            assertTrue(source.create().matches(Regex("^[a-z]{5}$")))
        }
    }
}
