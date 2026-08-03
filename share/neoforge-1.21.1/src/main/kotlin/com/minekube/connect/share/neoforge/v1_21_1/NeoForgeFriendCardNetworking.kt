package com.minekube.connect.share.neoforge.v1_21_1

import com.minekube.connect.share.fabric.ApprovedJoinTracker
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FriendCardIssuer
import com.minekube.connect.share.fabric.FriendCardReceiver
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

object NeoForgeFriendCardNetworking {
    private const val PROTOCOL = "1"
    private val installed = AtomicReference<Handlers?>()

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(PROTOCOL).optional()
        registrar.playToClient(
            FriendCardRequestPayload.TYPE,
            FriendCardRequestPayload.CODEC,
        ) { _, _ ->
            val handlers = installed.get() ?: return@playToClient
            val exchange = ConnectShareClient
                .consumeFriendCardExchangeConsent()
                ?: return@playToClient
            handlers.scope.launch(Dispatchers.IO) {
                handlers.issuer.issue().getOrNull()?.let { invitation ->
                    Minecraft.getInstance().execute {
                        if (Minecraft.getInstance().connection != null) {
                            PacketDistributor.sendToServer(
                                FriendCardPayload(
                                    invitation,
                                    exchange.relationshipId,
                                ),
                            )
                            handlers.scope.launch(Dispatchers.IO) {
                                handlers.receiver.confirmOutgoing(exchange.peerId)
                            }
                        }
                    }
                }
            }
        }
        registrar.playToServer(
            FriendCardPayload.TYPE,
            FriendCardPayload.CODEC,
        ) { payload, context ->
            val player = context.player() as? ServerPlayer
                ?: return@playToServer
            val handlers = installed.get() ?: return@playToServer
            val proof = handlers.approvedJoins.consume(
                player.gameProfile.name,
                player.uuid,
                payload.invitation,
            ) ?: return@playToServer
            handlers.scope.launch(Dispatchers.IO) {
                handlers.receiver.receive(
                    invitation = payload.invitation,
                    displayName = player.gameProfile.name,
                    authenticatedMinecraftUuid = proof.authenticatedMinecraftUuid,
                    allowAutomaticJoin = true,
                    relationshipId = payload.relationshipId,
                )
            }
        }
    }

    fun install(
        scope: CoroutineScope,
        issuer: FriendCardIssuer,
        receiver: FriendCardReceiver,
        approvedJoins: ApprovedJoinTracker,
    ) {
        installed.set(Handlers(scope, issuer, receiver, approvedJoins))
    }

    fun onPlayerLoggedIn(
        event: net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent,
    ) {
        val player = event.entity as? ServerPlayer ?: return
        val handlers = installed.get() ?: return
        if (handlers.approvedJoins.hasProof(player.gameProfile.name, player.uuid)) {
            PacketDistributor.sendToPlayer(player, FriendCardRequestPayload)
        }
    }

    private data class Handlers(
        val scope: CoroutineScope,
        val issuer: FriendCardIssuer,
        val receiver: FriendCardReceiver,
        val approvedJoins: ApprovedJoinTracker,
    )
}

private data class FriendCardPayload(
    val invitation: String,
    val relationshipId: UUID = UUID.randomUUID(),
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FriendCardPayload> = TYPE

    companion object {
        private const val MAX_CARD_CHARS = 16_384
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
                    buffer.writeUtf(payload.invitation, MAX_CARD_CHARS)
                    buffer.writeUUID(payload.relationshipId)
                },
                { buffer ->
                    FriendCardPayload(
                        buffer.readUtf(MAX_CARD_CHARS),
                        buffer.readUUID(),
                    )
                },
            )
    }
}

private data object FriendCardRequestPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<FriendCardRequestPayload> = TYPE

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
