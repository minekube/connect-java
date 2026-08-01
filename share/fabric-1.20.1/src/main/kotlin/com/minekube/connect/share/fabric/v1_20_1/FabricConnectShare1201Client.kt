package com.minekube.connect.share.fabric.v1_20_1

import com.minekube.connect.share.fabric.ApprovedJoinTracker
import com.minekube.connect.share.fabric.FriendCardIssuer
import com.minekube.connect.share.fabric.FriendCardReceiver
import com.minekube.connect.share.fabric.LoadedMod
import com.minekube.connect.share.fabric.ModSide
import com.minekube.connect.share.friend.ModLoader
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft

class FabricConnectShare1201Client : ClientModInitializer {
    override fun onInitializeClient() {
        ConnectShare1201Runtime(FabricPlatform).initialize()
    }

    private object FabricPlatform : ConnectShare1201Platform {
        private val loaderInstance = FabricLoader.getInstance()

        override val modVersion: String = loaderInstance
            .getModContainer("connect-share")
            .orElseThrow()
            .metadata.version.friendlyString
        override val loader = ModLoader.FABRIC
        override val loadedMods: List<LoadedMod> =
            loaderInstance.allMods.map { container ->
                val metadata = container.metadata
                LoadedMod(
                    id = metadata.id,
                    version = metadata.version.friendlyString,
                    side = when (metadata.environment.name) {
                        "CLIENT" -> ModSide.CLIENT
                        "SERVER" -> ModSide.SERVER
                        else -> ModSide.UNIVERSAL
                    },
                    builtIn = metadata.type == "builtin",
                )
            }
        override val configDirectory: Path = loaderInstance.configDir

        override fun onEndClientTick(callback: (Minecraft) -> Unit) {
            ClientTickEvents.END_CLIENT_TICK.register(callback)
        }

        override fun onClientStopping(callback: () -> Unit) {
            ClientLifecycleEvents.CLIENT_STOPPING.register { callback() }
        }

        override fun installFriendCardNetworking(
            scope: CoroutineScope,
            issuer: FriendCardIssuer,
            receiver: FriendCardReceiver,
            approvedJoins: ApprovedJoinTracker,
        ) {
            FriendCardNetworking.install(
                scope,
                issuer,
                receiver,
                approvedJoins,
            )
        }
    }
}
