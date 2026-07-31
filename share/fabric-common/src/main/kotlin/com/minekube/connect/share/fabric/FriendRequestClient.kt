package com.minekube.connect.share.fabric

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.minekube.connect.share.friend.FriendControlDecode
import com.minekube.connect.share.friend.FriendControlRequest
import com.minekube.connect.share.friend.FriendControlResponse
import com.minekube.connect.share.friend.FriendControlWire
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface FriendRequestFailure {
    val safeMessage: String

    data object Unreachable : FriendRequestFailure {
        override val safeMessage =
            "Your friend is not reachable right now"
    }

    data object Declined : FriendRequestFailure {
        override val safeMessage =
            "Your friend declined this request"
    }

    data object TimedOut : FriendRequestFailure {
        override val safeMessage =
            "Your friend did not answer in time"
    }

    data object InvalidResponse : FriendRequestFailure {
        override val safeMessage =
            "The friend request response was invalid"
    }
}

class FriendRequestClient(
    private val protocolVersion: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val decisionTimeout: Duration = Duration.ofSeconds(35),
) {
    suspend fun exchange(
        target: GuestJoinTarget,
        request: FriendControlRequest,
        onReceived: () -> Unit,
    ): Either<FriendRequestFailure, String> = withContext(ioDispatcher) {
        target.use {
            val route = target.routeTarget()
            val socket = Socket()
            val cancellation = coroutineContext[Job]
                ?.invokeOnCompletion { socket.close() }
            try {
                socket.connect(
                    route.socketAddress,
                    connectTimeout.toMillis().toInt(),
                )
                socket.soTimeout = READ_POLL_MILLIS
                socket.getOutputStream().apply {
                    write(
                        FriendControlWire.encodeRequest(
                            protocolVersion = protocolVersion,
                            serverAddress = route.handshakeAddress,
                            request = request,
                        ),
                    )
                    flush()
                }
                val deadline = System.nanoTime() + decisionTimeout.toNanos()
                var received = false
                var outcome: Either<FriendRequestFailure, String>? = null
                while (outcome == null) {
                    coroutineContext.ensureActive()
                    when (
                        val response =
                            socket.getInputStream().readResponse(deadline)
                    ) {
                        FriendControlResponse.Received -> {
                            if (!received) {
                                received = true
                                onReceived()
                            }
                        }

                        is FriendControlResponse.Accepted ->
                            outcome = response.invitation.right()

                        FriendControlResponse.Declined ->
                            outcome = FriendRequestFailure.Declined.left()

                        FriendControlResponse.TimedOut ->
                            outcome = FriendRequestFailure.TimedOut.left()

                        FriendControlResponse.Invalid ->
                            outcome =
                                FriendRequestFailure.InvalidResponse.left()
                    }
                }
                outcome
            } catch (cancellationFailure: CancellationException) {
                throw cancellationFailure
            } catch (_: SocketTimeoutException) {
                FriendRequestFailure.TimedOut.left()
            } catch (_: Exception) {
                FriendRequestFailure.Unreachable.left()
            } finally {
                cancellation?.dispose()
                socket.close()
            }
        }
    }

    private suspend fun InputStream.readResponse(
        deadlineNanos: Long,
    ): FriendControlResponse {
        val frame = ByteArrayOutputStream()
        var length = 0
        var shift = 0
        while (shift < 35) {
            val byte = readByte(deadlineNanos)
            frame.write(byte)
            length = length or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) {
                break
            }
            shift += 7
        }
        if (shift >= 35 || length !in 1..FriendControlWire.MAX_REQUEST_BYTES) {
            throw IllegalStateException("Friend response frame is invalid")
        }
        repeat(length) {
            frame.write(readByte(deadlineNanos))
        }
        return when (
            val decoded =
                FriendControlWire.decodeResponse(frame.toByteArray())
        ) {
            is FriendControlDecode.Decoded -> decoded.value
            FriendControlDecode.Incomplete,
            FriendControlDecode.Invalid,
            -> throw IllegalStateException(
                "Friend response frame is invalid",
            )
        }
    }

    private suspend fun InputStream.readByte(
        deadlineNanos: Long,
    ): Int {
        while (true) {
            coroutineContext.ensureActive()
            if (System.nanoTime() >= deadlineNanos) {
                throw SocketTimeoutException(
                    "Friend request decision timed out",
                )
            }
            try {
                return read().takeIf { it >= 0 }
                    ?: throw IllegalStateException(
                        "Friend request connection closed",
                    )
            } catch (_: SocketTimeoutException) {
                // Poll cancellation and the overall decision deadline.
            }
        }
    }

    private fun GuestJoinTarget.routeTarget(): RouteTarget = when (this) {
        is GuestJoinTarget.Connect -> {
            val parsed = parseAddress(publicAddress)
            RouteTarget(
                socketAddress = parsed,
                handshakeAddress = parsed.hostString,
            )
        }

        is GuestJoinTarget.Direct -> RouteTarget(
            socketAddress = localAddress,
            handshakeAddress = "connect-share",
        )
    }

    private fun parseAddress(value: String): InetSocketAddress {
        val trimmed = value.trim()
        if (trimmed.startsWith("[")) {
            val closing = trimmed.indexOf(']')
            require(closing > 1) { "Friend address is invalid" }
            val host = trimmed.substring(1, closing)
            val port = trimmed.substring(closing + 1)
                .removePrefix(":")
                .takeIf(String::isNotEmpty)
                ?.toInt()
                ?: DEFAULT_MINECRAFT_PORT
            return InetSocketAddress(host, port)
        }
        val colon = trimmed.lastIndexOf(':')
        val hasSingleColon =
            colon > 0 && trimmed.indexOf(':') == colon
        val host = if (hasSingleColon) {
            trimmed.substring(0, colon)
        } else {
            trimmed
        }
        val port = if (hasSingleColon) {
            trimmed.substring(colon + 1).toInt()
        } else {
            DEFAULT_MINECRAFT_PORT
        }
        return InetSocketAddress(host, port)
    }

    private data class RouteTarget(
        val socketAddress: InetSocketAddress,
        val handshakeAddress: String,
    )

    private companion object {
        const val DEFAULT_MINECRAFT_PORT = 25_565
        const val READ_POLL_MILLIS = 250
    }
}
