package com.minekube.connect.share.forge.v1_20_1

import com.minekube.connect.share.fabric.ApprovedJoinTracker
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FriendCardIssuer
import com.minekube.connect.share.fabric.FriendCardReceiver
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel

object ForgeFriendCardNetworking {
    private const val PROTOCOL = "1"
    private const val MAX_CARD_CHARS = 16_384
    private val channel: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation("connect_share", "friend_cards"),
        { PROTOCOL },
        { it == PROTOCOL },
        { it == PROTOCOL },
    )
    private val installed = AtomicReference<Handlers?>()

    fun install(
        scope: CoroutineScope,
        issuer: FriendCardIssuer,
        receiver: FriendCardReceiver,
        approvedJoins: ApprovedJoinTracker,
    ) {
        if (
            !installed.compareAndSet(
                null,
                Handlers(scope, issuer, receiver, approvedJoins),
            )
        ) {
            return
        }
        channel.messageBuilder(
            FriendCardMessage::class.java,
            0,
            NetworkDirection.PLAY_TO_SERVER,
        )
            .encoder { message, buffer -> buffer.writeUtf(message.invitation, MAX_CARD_CHARS) }
            .decoder { buffer -> FriendCardMessage(buffer.readUtf(MAX_CARD_CHARS)) }
            .consumerMainThread { message, source ->
                val player = source.get().sender ?: return@consumerMainThread
                val handlers = installed.get() ?: return@consumerMainThread
                val proof = handlers.approvedJoins.consume(
                    player.gameProfile.name,
                    player.uuid,
                ) ?: return@consumerMainThread
                handlers.scope.launch(Dispatchers.IO) {
                    handlers.receiver.receive(
                        invitation = message.invitation,
                        displayName = player.gameProfile.name,
                        authenticatedMinecraftUuid = proof.authenticatedMinecraftUuid,
                        allowAutomaticJoin = true,
                    )
                }
            }
            .add()
        channel.messageBuilder(
            FriendCardRequestMessage::class.java,
            1,
            NetworkDirection.PLAY_TO_CLIENT,
        )
            .encoder { _, _ -> }
            .decoder { FriendCardRequestMessage }
            .consumerMainThread { _, _ ->
                val handlers = installed.get() ?: return@consumerMainThread
                val exchange = ConnectShareClient
                    .consumeFriendCardExchangeConsent()
                    ?: return@consumerMainThread
                handlers.scope.launch(Dispatchers.IO) {
                    handlers.issuer.issue().getOrNull()?.let { invitation ->
                        Minecraft.getInstance().execute {
                            if (Minecraft.getInstance().connection != null) {
                                channel.sendToServer(FriendCardMessage(invitation))
                                handlers.scope.launch(Dispatchers.IO) {
                                    handlers.receiver.confirmOutgoing(exchange.peerId)
                                }
                            }
                        }
                    }
                }
            }
            .add()
        MinecraftForge.EVENT_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            val handlers = installed.get() ?: return@addListener
            if (handlers.approvedJoins.hasProof(player.gameProfile.name, player.uuid)) {
                channel.send(
                    PacketDistributor.PLAYER.with { player },
                    FriendCardRequestMessage,
                )
            }
        }
    }

    private data class Handlers(
        val scope: CoroutineScope,
        val issuer: FriendCardIssuer,
        val receiver: FriendCardReceiver,
        val approvedJoins: ApprovedJoinTracker,
    )

    private data class FriendCardMessage(
        val invitation: String,
    )

    private data object FriendCardRequestMessage
}
