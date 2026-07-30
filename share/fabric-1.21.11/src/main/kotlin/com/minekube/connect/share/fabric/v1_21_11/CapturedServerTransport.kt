package com.minekube.connect.share.fabric.v1_21_11

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup

object CapturedServerTransport {
    private val captureLock = Any()

    @Volatile
    private var armed: ArmedCapture? = null

    @JvmStatic
    fun arm(): CaptureLease = synchronized(captureLock) {
        check(armed == null) { "A Minecraft transport capture is already active" }
        val capture = ArmedCapture(Thread.currentThread())
        armed = capture
        CaptureLease(capture)
    }

    @JvmStatic
    fun isShareStartArmed(): Boolean =
        armed?.owner === Thread.currentThread()

    @JvmStatic
    fun captureChildInitializer(
        initializer: ChannelInitializer<Channel>,
    ): ChannelInitializer<Channel> {
        synchronized(captureLock) {
            armed
                ?.takeIf { it.owner === Thread.currentThread() }
                ?.childInitializer = initializer
        }
        return initializer
    }

    @JvmStatic
    fun captureEventLoopGroup(group: EventLoopGroup): EventLoopGroup {
        synchronized(captureLock) {
            armed
                ?.takeIf { it.owner === Thread.currentThread() }
                ?.eventLoopGroup = group
        }
        return group
    }

    internal fun complete(
        expected: ArmedCapture,
    ): Either<CaptureFailure, CapturedTransport> = synchronized(captureLock) {
        val current = armed
        if (current !== expected || current.owner !== Thread.currentThread()) {
            return@synchronized CaptureFailure.Incomplete.left()
        }
        armed = null
        val initializer = current.childInitializer
        val group = current.eventLoopGroup
        if (initializer == null || group == null) {
            CaptureFailure.Incomplete.left()
        } else {
            CapturedTransport(initializer, group).right()
        }
    }

    internal fun cancel(expected: ArmedCapture) {
        synchronized(captureLock) {
            if (armed === expected) {
                armed = null
            }
        }
    }

    internal class ArmedCapture(
        val owner: Thread,
        var childInitializer: ChannelInitializer<Channel>? = null,
        var eventLoopGroup: EventLoopGroup? = null,
    )
}

class CaptureLease internal constructor(
    private val capture: CapturedServerTransport.ArmedCapture,
) : AutoCloseable {
    private var completed = false

    fun complete(): Either<CaptureFailure, CapturedTransport> {
        check(!completed) { "Minecraft transport capture is already complete" }
        completed = true
        return CapturedServerTransport.complete(capture)
    }

    override fun close() {
        if (!completed) {
            completed = true
            CapturedServerTransport.cancel(capture)
        }
    }
}

data class CapturedTransport(
    val childInitializer: ChannelInitializer<Channel>,
    val eventLoopGroup: EventLoopGroup,
)

sealed interface CaptureFailure {
    data object Incomplete : CaptureFailure
}
