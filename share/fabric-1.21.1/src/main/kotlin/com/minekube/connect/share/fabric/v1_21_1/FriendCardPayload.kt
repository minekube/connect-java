package com.minekube.connect.share.fabric.v1_21_1

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import java.util.UUID

data class FriendCardPayload(
    val invitation: String,
    val relationshipId: UUID = UUID.randomUUID(),
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FriendCardPayload> = TYPE

    companion object {
        const val MAX_CARD_CHARS = 16_384

        val TYPE: CustomPacketPayload.Type<FriendCardPayload> =
            CustomPacketPayload.Type(
                ResourceLocation.fromNamespaceAndPath(
                    "connect-share",
                    "friend-card",
                ),
            )

        val CODEC: StreamCodec<FriendlyByteBuf, FriendCardPayload> =
            CustomPacketPayload.codec(
                { payload, buffer ->
                    buffer.writeUtf(
                        payload.invitation,
                        MAX_CARD_CHARS,
                    )
                    buffer.writeUUID(payload.relationshipId)
                },
                { buffer ->
                    FriendCardPayload(
                        invitation = buffer.readUtf(MAX_CARD_CHARS),
                        relationshipId = buffer.readUUID(),
                    )
                },
            )
    }
}

data object FriendCardRequestPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FriendCardRequestPayload> =
        TYPE

    val TYPE: CustomPacketPayload.Type<FriendCardRequestPayload> =
        CustomPacketPayload.Type(
            ResourceLocation.fromNamespaceAndPath(
                "connect-share",
                "friend-card-request",
            ),
        )

    val CODEC: StreamCodec<FriendlyByteBuf, FriendCardRequestPayload> =
        StreamCodec.unit(FriendCardRequestPayload)
}
