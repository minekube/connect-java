package com.minekube.connect.share.neoforge.v1_21_1

import com.minekube.connect.share.fabric.ApprovedJoinTracker
import com.minekube.connect.share.fabric.FriendCardIssuer
import com.minekube.connect.share.fabric.FriendCardReceiver
import com.minekube.connect.share.fabric.LoadedMod
import com.minekube.connect.share.fabric.ModSide
import com.minekube.connect.share.fabric.v1_21_1.ConnectShare1211Platform
import com.minekube.connect.share.fabric.v1_21_1.ConnectShare1211Runtime
import com.minekube.connect.share.friend.ModLoader
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import net.minecraft.client.Minecraft
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.GameShuttingDownEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@Mod("connect_share")
class NeoForgeConnectShare1211Client(modEventBus: IEventBus) {
    private val platform = NeoForgePlatform()

    init {
        modEventBus.addListener(
            RegisterPayloadHandlersEvent::class.java,
        ) { event ->
            NeoForgeFriendCardNetworking.register(event)
        }
        ConnectShare1211Runtime(platform).initialize()
        NeoForge.EVENT_BUS.register(platform)
    }

    private class NeoForgePlatform : ConnectShare1211Platform {
        private val tickCallbacks = mutableListOf<(Minecraft) -> Unit>()
        private val stopCallbacks = mutableListOf<() -> Unit>()

        override val modVersion: String = ModList.get()
            .getModContainerById("connect_share")
            .orElseThrow()
            .modInfo.version.toString()
        override val loader = ModLoader.NEOFORGE
        override val loadedMods: List<LoadedMod> = ModList.get().mods.map {
            LoadedMod(
                id = it.modId,
                version = it.version.toString(),
                side = ModSide.UNIVERSAL,
                builtIn = it.modId == "minecraft" || it.modId == "neoforge",
            )
        }
        override val configDirectory: Path = FMLPaths.CONFIGDIR.get()

        override fun onEndClientTick(callback: (Minecraft) -> Unit) {
            tickCallbacks += callback
        }

        override fun onClientStopping(callback: () -> Unit) {
            stopCallbacks += callback
        }

        override fun installFriendCardNetworking(
            scope: CoroutineScope,
            issuer: FriendCardIssuer,
            receiver: FriendCardReceiver,
            approvedJoins: ApprovedJoinTracker,
        ) = NeoForgeFriendCardNetworking.install(
            scope,
            issuer,
            receiver,
            approvedJoins,
        )

        @SubscribeEvent
        fun onPlayerLoggedIn(event: net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent) {
            NeoForgeFriendCardNetworking.onPlayerLoggedIn(event)
        }

        @SubscribeEvent
        fun onClientTick(event: ClientTickEvent.Post) {
            val minecraft = Minecraft.getInstance()
            tickCallbacks.forEach { it(minecraft) }
        }

        @SubscribeEvent
        fun onGameShuttingDown(event: GameShuttingDownEvent) {
            stopCallbacks.forEach { it() }
        }
    }
}
