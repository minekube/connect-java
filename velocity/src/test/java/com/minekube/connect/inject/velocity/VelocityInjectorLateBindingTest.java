package com.minekube.connect.inject.velocity;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VelocityInjectorLateBindingTest {
    @Test
    void localTunnelUsesFrontendInitializerInstalledAfterConnectStartup() {
        AtomicReference<ChannelInitializer<Channel>> current = new AtomicReference<>(initializer("velocity-decoder"));
        VelocityInjector.CurrentVelocityChannelInitializer tunnelInitializer =
                new VelocityInjector.CurrentVelocityChannelInitializer(current::get);

        ChannelInitializer<Channel> connectInitializer = current.get();
        current.set(wrappingInitializer(connectInitializer, "packetevents-decoder"));

        EmbeddedChannel channel = new EmbeddedChannel(tunnelInitializer);

        assertNotNull(channel.pipeline().get("velocity-decoder"));
        assertNotNull(channel.pipeline().get("packetevents-decoder"));
        channel.finishAndReleaseAll();
    }

    private static ChannelInitializer<Channel> initializer(String handlerName) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(handlerName, new ChannelInboundHandlerAdapter());
            }
        };
    }

    private static ChannelInitializer<Channel> wrappingInitializer(
            ChannelInitializer<Channel> wrapped,
            String handlerName) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(wrapped);
                channel.pipeline().addLast(handlerName, new ChannelInboundHandlerAdapter());
            }
        };
    }
}
