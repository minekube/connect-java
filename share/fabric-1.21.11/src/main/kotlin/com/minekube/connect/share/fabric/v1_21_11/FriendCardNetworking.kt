package com.minekube.connect.share.fabric.v1_21_11

import com.minekube.connect.share.fabric.ApprovedJoinTracker
import com.minekube.connect.share.fabric.ConnectShareClient
import com.minekube.connect.share.fabric.FriendCardIssuer
import com.minekube.connect.share.fabric.FriendCardReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

object FriendCardNetworking {
    fun install(
        scope: CoroutineScope,
        issuer: FriendCardIssuer,
        receiver: FriendCardReceiver,
        approvedJoins: ApprovedJoinTracker,
    ) {
        PayloadTypeRegistry.playC2S().register(
            FriendCardPayload.TYPE,
            FriendCardPayload.CODEC,
        )
        PayloadTypeRegistry.playS2C().register(
            FriendCardRequestPayload.TYPE,
            FriendCardRequestPayload.CODEC,
        )
        ServerPlayNetworking.registerGlobalReceiver(
            FriendCardPayload.TYPE,
        ) { payload, context ->
            context.server().execute {
                val player = context.player()
                val proof = approvedJoins.consume(
                    player.gameProfile.name(),
                    player.uuid,
                ) ?: return@execute
                receiver.receive(
                    invitation = payload.invitation,
                    displayName = player.gameProfile.name(),
                    authenticatedMinecraftUuid =
                        proof.authenticatedMinecraftUuid,
                )
            }
        }
        ServerPlayConnectionEvents.JOIN.register(
            ServerPlayConnectionEvents.Join { handler, _, _ ->
                val player = handler.player
                if (
                    approvedJoins.hasProof(
                        player.gameProfile.name(),
                        player.uuid,
                    ) &&
                    ServerPlayNetworking.canSend(
                        player,
                        FriendCardRequestPayload.TYPE,
                    )
                ) {
                    ServerPlayNetworking.send(
                        player,
                        FriendCardRequestPayload,
                    )
                }
            },
        )
        ClientPlayNetworking.registerGlobalReceiver(
            FriendCardRequestPayload.TYPE,
        ) { _, context ->
            if (!ConnectShareClient.consumeFriendCardExchangeConsent()) {
                return@registerGlobalReceiver
            }
            val client = context.client()
            scope.launch(Dispatchers.IO) {
                issuer.issue().getOrNull()?.let { invitation ->
                    client.execute {
                        if (
                            client.connection != null &&
                            ClientPlayNetworking.canSend(
                                FriendCardPayload.TYPE,
                            )
                        ) {
                            ClientPlayNetworking.send(
                                FriendCardPayload(invitation),
                            )
                        }
                    }
                }
            }
        }
    }
}
