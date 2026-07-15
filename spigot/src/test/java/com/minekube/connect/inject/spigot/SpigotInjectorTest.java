package com.minekube.connect.inject.spigot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class SpigotInjectorTest {
    @Test
    void passthroughChannelTrackingIsRemovedOnDisconnect() {
        SpigotInjector injector = new SpigotInjector(null, null, "packet_handler", false);
        EmbeddedChannel channel = new EmbeddedChannel();

        assertTrue(injector.trackPassthroughClient(channel));

        channel.close().syncUninterruptibly();

        assertFalse(injector.removeInjectedClient(channel));
    }
}
