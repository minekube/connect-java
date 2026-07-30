package com.minekube.connect.share.fabric

import arrow.core.Either
import com.minekube.connect.share.identity.CredentialValidationError
import com.minekube.connect.share.identity.EndpointCredentialValidator
import com.minekube.connect.share.identity.EndpointIdentity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionRejection
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchRequest
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchResponse
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class WatchEndpointCredentialValidator(
    private val client: OkHttpClient,
    private val watchUrl: HttpUrl,
    private val timeout: Duration = 10.seconds,
) : EndpointCredentialValidator {
    override suspend fun validate(
        identity: EndpointIdentity,
    ): Either<CredentialValidationError, Unit> =
        withTimeoutOrNull(timeout) {
            awaitValidation(identity)
        } ?: Either.Left(
            CredentialValidationError.Network(
                "Connect credential validation timed out",
            ),
        )

    private suspend fun awaitValidation(
        identity: EndpointIdentity,
    ): Either<CredentialValidationError, Unit> = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean()
        val socketReference = AtomicReference<WebSocket>()
        val request = Request.Builder()
            .url(watchUrl)
            .header("Authorization", "Bearer ${identity.token}")
            .header("Connect-Endpoint", identity.endpoint)
            .header("Connect-Platform", "Fabric")
            .build()

        fun complete(result: Either<CredentialValidationError, Unit>) {
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(result)
            }
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.close(NORMAL_CLOSE, "credentials validated")
                complete(Either.Right(Unit))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                rejectProposal(webSocket, bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                complete(
                    Either.Left(
                        CredentialValidationError.Network(
                            "Connect credential validation closed before authentication",
                        ),
                    ),
                )
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                val error = when (response?.code) {
                    401, 403 -> CredentialValidationError.Rejected(
                        "Connect rejected the endpoint credentials",
                    )

                    else -> CredentialValidationError.Network(
                        "Could not reach Connect to validate the endpoint credentials",
                    )
                }
                response?.close()
                complete(Either.Left(error))
            }
        }

        val socket = client.newWebSocket(request, listener)
        socketReference.set(socket)
        continuation.invokeOnCancellation {
            completed.set(true)
            socketReference.get()?.cancel()
        }
    }

    internal fun rejectProposal(webSocket: WebSocket, bytes: ByteString) {
        val response = runCatching {
            WatchResponse.parseFrom(bytes.toByteArray())
        }.getOrElse {
            webSocket.close(PROTOCOL_ERROR_CLOSE, "invalid watch response")
            return
        }
        val rejection = SessionRejection.newBuilder()
            .setId(response.session.id)
            .build()
        val request = WatchRequest.newBuilder()
            .setSessionRejection(rejection)
            .build()
        webSocket.send(ByteString.of(*request.toByteArray()))
        webSocket.close(NORMAL_CLOSE, "credential validation rejects proposals")
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        const val PROTOCOL_ERROR_CLOSE = 1002
    }
}
