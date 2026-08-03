package com.minekube.connect.share.fabric.v1_20_1

import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

data class FriendCardPayload(
    val invitation: String,
    val relationshipId: UUID = UUID.randomUUID(),
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
        buffer.writeUUID(payload.relationshipId)
    }

    fun decode(buffer: FriendlyByteBuf): FriendCardPayload =
        FriendCardPayload(
            invitation = buffer.readUtf(FriendCardChannels.MAX_CARD_CHARS),
            relationshipId = buffer.readUUID(),
        )
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
