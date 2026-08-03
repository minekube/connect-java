package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.identity.CredentialSource
import com.minekube.connect.share.identity.CredentialValidationError
import com.minekube.connect.share.identity.EndpointIdentity
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString

class WatchEndpointCredentialValidatorTest {
    @Test
    fun `successful validation sends credential headers and closes immediately`() = runBlocking {
        MockWebServer().use { server ->
            val closed = CountDownLatch(1)
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            closed.countDown()
                            webSocket.close(code, reason)
                        }
                    },
                ),
            )
            server.start()

            val result = validator(server).validate(identity)
            val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            assertIs<Either.Right<Unit>>(result)
            assertEquals("Bearer ${identity.token}", request.getHeader("Authorization"))
            assertEquals(identity.endpoint, request.getHeader("Connect-Endpoint"))
            assertEquals("Fabric", request.getHeader("Connect-Platform"))
            assertEquals(true, closed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `unexpected proposal is rejected without opening a local tunnel`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val socket = RecordingWebSocket()
            val proposal = minekube.connect.v1alpha1.WatchServiceOuterClass.WatchResponse
                .newBuilder()
                .setSession(Session.newBuilder().setId("proposal-1"))
                .build()

            validator(server).rejectProposal(
                socket,
                ByteString.of(*proposal.toByteArray()),
            )

            val rejection = WatchRequest.parseFrom(assertNotNull(socket.binary).toByteArray())
            assertEquals("proposal-1", rejection.sessionRejection.id)
            assertEquals(1000, socket.closeCode)
        }
    }

    @Test
    fun `unauthorized response is sanitized`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody(identity.token))
            server.start()

            val result = validator(server).validate(identity)
            val error = assertIs<Either.Left<CredentialValidationError>>(result).value

            assertIs<CredentialValidationError.Rejected>(error)
            assertFalse(error.safeMessage.contains(identity.token))
            assertFalse(error.toString().contains(identity.token))
        }
    }

    @Test
    fun `transport failure returns a safe network error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.start()

            val result = validator(server).validate(identity)
            val error = assertIs<Either.Left<CredentialValidationError>>(result).value

            assertIs<CredentialValidationError.Network>(error)
            assertFalse(error.safeMessage.contains(identity.token))
            assertFalse(error.toString().contains(identity.token))
        }
    }

    @Test
    fun `validation timeout returns a safe network error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()

            val result = validator(server, 100.milliseconds).validate(identity)
            val error = assertIs<Either.Left<CredentialValidationError>>(result).value

            assertIs<CredentialValidationError.Network>(error)
            assertFalse(error.safeMessage.contains(identity.token))
        }
    }

    @Test
    fun `caller cancellation remains cancellation`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val validation = async {
                validator(server, 30.seconds).validate(identity)
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

            validation.cancel(CancellationException("screen closed"))

            assertFailsWith<CancellationException> {
                validation.await()
            }
        }
    }

    private fun validator(
        server: MockWebServer,
        timeout: Duration = 2.seconds,
    ) = WatchEndpointCredentialValidator(
        client = OkHttpClient(),
        watchUrl = server.url("/watch"),
        timeout = timeout,
    )

    private class RecordingWebSocket : WebSocket {
        var binary: ByteString? = null
        var closeCode: Int? = null

        override fun request(): Request = Request.Builder()
            .url("http://localhost/")
            .build()

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = false

        override fun send(bytes: ByteString): Boolean {
            binary = bytes
            return true
        }

        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }

        override fun cancel() = Unit
    }

    private companion object {
        val identity = EndpointIdentity(
            endpoint = "amber-fox",
            token = "T-AAAAAAAAAAAAAAAAAAAA",
            endpointSource = CredentialSource.IMPORTED,
            tokenSource = CredentialSource.IMPORTED,
        )
    }
}
