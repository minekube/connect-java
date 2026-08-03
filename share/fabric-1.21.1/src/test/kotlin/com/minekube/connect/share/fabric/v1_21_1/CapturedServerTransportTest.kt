package com.minekube.connect.share.fabric.v1_21_1

import com.minekube.connect.share.CaptureFailure
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.DefaultEventLoopGroup
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CapturedServerTransportTest {
    @Test
    fun `captures the tagged vanilla initializer and group only for the armed thread`() {
        val initializer = NoopInitializer
        val group = DefaultEventLoopGroup(1)
        try {
            val lease = CapturedServerTransport.arm()

            assertTrue(CapturedServerTransport.isShareStartArmed())
            val taggedInitializer =
                CapturedServerTransport.captureChildInitializer(initializer)
            assertNotSame(initializer, taggedInitializer)
            assertSame(group, CapturedServerTransport.captureEventLoopGroup(group))

            var otherThreadArmed = true
            thread {
                otherThreadArmed = CapturedServerTransport.isShareStartArmed()
            }.join()

            val captured = lease.complete().getOrNull()
            requireNotNull(captured)
            assertSame(taggedInitializer, captured.childInitializer)
            assertSame(group, captured.eventLoopGroup)
            assertFalse(otherThreadArmed)
            assertFalse(CapturedServerTransport.isShareStartArmed())
        } finally {
            group.shutdownGracefully().syncUninterruptibly()
        }
    }

    @Test
    fun `incomplete capture is typed and always disarms`() {
        val lease = CapturedServerTransport.arm()

        val failure = lease.complete().leftOrNull()

        assertEquals(CaptureFailure.Incomplete, failure)
        assertFalse(CapturedServerTransport.isShareStartArmed())
    }

    private object NoopInitializer : ChannelInitializer<Channel>() {
        override fun initChannel(channel: Channel) = Unit
    }
}
