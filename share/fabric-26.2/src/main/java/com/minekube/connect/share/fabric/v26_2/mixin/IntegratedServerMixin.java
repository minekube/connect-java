package com.minekube.connect.share.fabric.v26_2.mixin;

import com.minekube.connect.share.CapturedServerTransport;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Redirect(
            method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;Lnet/minecraft/world/level/GameType;ZI)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/server/LanServerPinger;start()V"))
    private void connectShare$suppressLanAdvertisement(LanServerPinger pinger) {
        if (CapturedServerTransport.isShareStartArmed()) {
            pinger.interrupt();
        } else {
            pinger.start();
        }
    }
}
