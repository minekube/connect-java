package com.minekube.connect.share.fabric.v1_20_1

import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import net.minecraft.network.FriendlyByteBuf

class FriendCardPayloadTest {
    @Test
    fun `signed friend card survives the network payload codec`() {
        val invitation = "minekube://share/" + "signed-card".repeat(500)
        val buffer = FriendlyByteBuf(Unpooled.buffer())

        FriendCardPayload.CODEC.encode(
            buffer,
            FriendCardPayload(invitation),
        )

        assertEquals(
            invitation,
            FriendCardPayload.CODEC.decode(buffer).invitation,
        )
    }

    @Test
    fun `friend card request has a zero data payload`() {
        val buffer = FriendlyByteBuf(Unpooled.buffer())

        FriendCardRequestPayload.CODEC.encode(
            buffer,
            FriendCardRequestPayload,
        )

        assertEquals(0, buffer.readableBytes())
        assertSame(
            FriendCardRequestPayload,
            FriendCardRequestPayload.CODEC.decode(buffer),
        )
    }
}
