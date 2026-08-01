package com.minekube.connect.share.fabric.v1_20_1.mixin;

import java.net.DatagramSocket;
import net.minecraft.client.server.LanServerPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LanServerPinger.class)
public interface LanServerPingerAccessor {
    @Accessor("socket")
    DatagramSocket getConnectShareSocket();
}
