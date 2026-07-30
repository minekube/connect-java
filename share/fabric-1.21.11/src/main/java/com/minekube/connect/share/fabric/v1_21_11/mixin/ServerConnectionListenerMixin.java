package com.minekube.connect.share.fabric.v1_21_11.mixin;

import com.minekube.connect.share.fabric.v1_21_11.CapturedServerTransport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import java.net.InetAddress;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerMixin {
    @ModifyVariable(
            method = "startTcpServerListener",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private InetAddress connectShare$forceLoopback(InetAddress requestedAddress) {
        return CapturedServerTransport.isShareStartArmed()
                ? InetAddress.getLoopbackAddress()
                : requestedAddress;
    }

    @ModifyArg(
            method = "startTcpServerListener",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;childHandler"
                            + "(Lio/netty/channel/ChannelHandler;)"
                            + "Lio/netty/bootstrap/ServerBootstrap;"),
            index = 0)
    @SuppressWarnings("unchecked")
    private ChannelHandler connectShare$captureChildInitializer(ChannelHandler handler) {
        if (CapturedServerTransport.isShareStartArmed()
                && handler instanceof ChannelInitializer<?>) {
            CapturedServerTransport.captureChildInitializer(
                    (ChannelInitializer<Channel>) handler);
        }
        return handler;
    }

    @ModifyArg(
            method = "startTcpServerListener",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;group"
                            + "(Lio/netty/channel/EventLoopGroup;)"
                            + "Lio/netty/bootstrap/ServerBootstrap;"),
            index = 0)
    private EventLoopGroup connectShare$captureEventLoopGroup(EventLoopGroup group) {
        return CapturedServerTransport.captureEventLoopGroup(group);
    }
}
