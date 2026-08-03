package com.minekube.connect.share.fabric.v26_2.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.server.LanServerPinger;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(IntegratedServer.class)
public interface IntegratedServerAccessor {
    @Accessor("lanPinger")
    @Nullable LanServerPinger getConnectShareLanPinger();

    @Accessor("lanPinger")
    void setConnectShareLanPinger(@Nullable LanServerPinger pinger);
}
