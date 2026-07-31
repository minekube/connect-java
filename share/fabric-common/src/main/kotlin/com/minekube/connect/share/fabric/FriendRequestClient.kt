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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeout: Duration = Duration.ofSeconds(5),
    private val decisionTimeout: Duration = Duration.ofSeconds(35),
) {
    suspend fun exchange(
        target: GuestJoinTarget.Direct,
        request: FriendControlRequest,
        onReceived: () -> Unit,
    ): Either<FriendRequestFailure, String> = withContext(ioDispatcher) {
        target.use {
            val socket = Socket()
            val cancellation = coroutineContext[Job]
                ?.invokeOnCompletion { socket.close() }
            try {
                socket.connect(
                    target.localAddress,
                    connectTimeout.toMillis().toInt(),
                )
                socket.soTimeout = READ_POLL_MILLIS
                socket.getOutputStream().apply {
                    write(
                        FriendControlWire.encodeRequest(
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

    private companion object {
        const val READ_POLL_MILLIS = 250
    }
}
