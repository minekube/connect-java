package com.minekube.connect.share.fabric.v1_20_1

import com.minekube.connect.share.fabric.ApprovedJoinTracker
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FriendCardIssuer
import com.minekube.connect.share.fabric.FriendCardReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

object FriendCardNetworking {
    fun install(
        scope: CoroutineScope,
        issuer: FriendCardIssuer,
        receiver: FriendCardReceiver,
        approvedJoins: ApprovedJoinTracker,
    ) {
        ServerPlayNetworking.registerGlobalReceiver(
            FriendCardChannels.CARD,
        ) { server, player, _, buffer, _ ->
            val payload = runCatching {
                FriendCardCodec.decode(buffer)
            }.getOrNull() ?: return@registerGlobalReceiver
            server.execute {
                val proof = approvedJoins.consume(
                    player.gameProfile.name,
                    player.uuid,
                ) ?: return@execute
                receiver.receive(
                    invitation = payload.invitation,
                    displayName = player.gameProfile.name,
                    authenticatedMinecraftUuid = proof.authenticatedMinecraftUuid,
                    allowAutomaticJoin = true,
                    relationshipId = payload.relationshipId,
                )
            }
        }
        ServerPlayConnectionEvents.JOIN.register(
            ServerPlayConnectionEvents.Join { handler, _, _ ->
                val player = handler.player
                if (
                    approvedJoins.hasProof(player.gameProfile.name, player.uuid) &&
                    ServerPlayNetworking.canSend(player, FriendCardChannels.REQUEST)
                ) {
                    ServerPlayNetworking.send(
                        player,
                        FriendCardChannels.REQUEST,
                        PacketByteBufs.empty(),
                    )
                }
            },
        )
        ClientPlayNetworking.registerGlobalReceiver(
            FriendCardChannels.REQUEST,
        ) { client, _, _, _ ->
            val exchange =
                ConnectShareClient.consumeFriendCardExchangeConsent()
                    ?: return@registerGlobalReceiver
            scope.launch(Dispatchers.IO) {
                issuer.issue().getOrNull()?.let { invitation ->
                    client.execute {
                        if (
                            client.connection != null &&
                            ClientPlayNetworking.canSend(FriendCardChannels.CARD)
                        ) {
                            val buffer = PacketByteBufs.create()
                            buffer.writeUtf(
                                invitation,
                                FriendCardChannels.MAX_CARD_CHARS,
                            )
                            buffer.writeUUID(exchange.relationshipId)
                            ClientPlayNetworking.send(FriendCardChannels.CARD, buffer)
                            scope.launch(Dispatchers.IO) {
                                receiver.confirmOutgoing(exchange.peerId)
                            }
                        }
                    }
                }
            }
        }
    }
}
