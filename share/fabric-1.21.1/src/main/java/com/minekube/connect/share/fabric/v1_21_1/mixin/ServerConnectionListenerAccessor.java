package com.minekube.connect.share.fabric.v1_21_1.mixin;

import io.netty.channel.ChannelFuture;
import java.util.List;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerConnectionListener.class)
public interface ServerConnectionListenerAccessor {
    @Accessor("channels")
    List<ChannelFuture> getConnectShareChannels();
}
