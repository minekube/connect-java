package com.minekube.connect.share.forge.v1_20_1

import com.minekube.connect.share.fabric.ApprovedJoinTracker
import com.minekube.connect.share.fabric.FriendCardIssuer
import com.minekube.connect.share.fabric.FriendCardReceiver
import com.minekube.connect.share.fabric.LoadedMod
import com.minekube.connect.share.fabric.ModSide
import com.minekube.connect.share.fabric.v1_20_1.ConnectShare1201Platform
import com.minekube.connect.share.fabric.v1_20_1.ConnectShare1201Runtime
import com.minekube.connect.share.friend.ModLoader
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import net.minecraft.client.Minecraft
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.GameShuttingDownEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLPaths

@Mod(value = "connect_share")
class ForgeConnectShare1201Client {
    private val platform = ForgePlatform()

    init {
        ConnectShare1201Runtime(platform).initialize()
        MinecraftForge.EVENT_BUS.register(platform)
    }

    private class ForgePlatform : ConnectShare1201Platform {
        private val tickCallbacks = mutableListOf<(Minecraft) -> Unit>()
        private val stopCallbacks = mutableListOf<() -> Unit>()

        override val modVersion: String = ModList.get()
            .getModContainerById("connect_share")
            .orElseThrow()
            .modInfo.version.toString()
        override val loader = ModLoader.FORGE
        override val loadedMods: List<LoadedMod> = ModList.get().mods.map {
            LoadedMod(
                id = it.modId,
                version = it.version.toString(),
                side = ModSide.UNIVERSAL,
                builtIn = it.modId == "minecraft" || it.modId == "forge",
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
        ) = Unit

        @SubscribeEvent
        fun onClientTick(event: TickEvent.ClientTickEvent) {
            if (event.phase == TickEvent.Phase.END) {
                val minecraft = Minecraft.getInstance()
                tickCallbacks.forEach { it(minecraft) }
            }
        }

        @SubscribeEvent
        fun onGameShuttingDown(event: GameShuttingDownEvent) {
            stopCallbacks.forEach { it() }
        }
    }
}
