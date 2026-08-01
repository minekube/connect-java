package com.minekube.connect.share.fabric.v1_20_1

import net.minecraft.resources.ResourceLocation
import net.minecraft.network.FriendlyByteBuf

data class FriendCardPayload(
    val invitation: String,
) {
    companion object {
        val CODEC = FriendCardCodec
    }
}

data object FriendCardRequestPayload {
    val CODEC = FriendCardRequestCodec
}

object FriendCardCodec {
    fun encode(buffer: FriendlyByteBuf, payload: FriendCardPayload) {
        buffer.writeUtf(payload.invitation, FriendCardChannels.MAX_CARD_CHARS)
    }

    fun decode(buffer: FriendlyByteBuf): FriendCardPayload =
        FriendCardPayload(buffer.readUtf(FriendCardChannels.MAX_CARD_CHARS))
}

object FriendCardRequestCodec {
    fun encode(buffer: FriendlyByteBuf, payload: FriendCardRequestPayload) = Unit
    fun decode(buffer: FriendlyByteBuf): FriendCardRequestPayload =
        FriendCardRequestPayload
}

object FriendCardChannels {
    val CARD = ResourceLocation("connect-share", "friend-card")
    val REQUEST = ResourceLocation("connect-share", "friend-card-request")
    const val MAX_CARD_CHARS = 16_384
}
