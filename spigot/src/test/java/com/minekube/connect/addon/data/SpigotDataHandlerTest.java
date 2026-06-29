package com.minekube.connect.addon.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import java.net.InetSocketAddress;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class SpigotDataHandlerTest {
    @Test
    void usesSpoofedPlayerAddressWhenLocalChannelHasNoInetRemoteAddress() {
        String address = SpigotDataHandler.playerRemoteAddressAsString(
                new LocalAddress("connect-local"),
                InetSocketAddress.createUnresolved("93.201.72.37", 0));

        assertEquals("93.201.72.37", address);
    }

    @Test
    void removeSelfIsIdempotentWhenHandshakeReplacementReentersPipeline() throws Exception {
        SpigotDataHandler handler = new SpigotDataHandler(null, "packet-handler", null, null);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        Method removeSelf = SpigotDataHandler.class.getDeclaredMethod("removeSelf");
        removeSelf.setAccessible(true);

        removeSelf.invoke(handler);

        assertDoesNotThrow(() -> removeSelf.invoke(handler));
        channel.finishAndReleaseAll();
    }
}
