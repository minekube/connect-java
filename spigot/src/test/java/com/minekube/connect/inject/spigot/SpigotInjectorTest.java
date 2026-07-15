package com.minekube.connect.inject.spigot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.api.logger.ConnectLogger;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class SpigotInjectorTest {
    @Test
    void retainsLegacyTwoArgumentConstructor() throws Exception {
        Constructor<SpigotInjector> constructor = SpigotInjector.class.getConstructor(
                ConnectLogger.class, boolean.class);

        assertNotNull(constructor.newInstance(new Object[] {null, false}));
    }

    @Test
    void passthroughChannelTrackingIsRemovedOnDisconnect() {
        SpigotInjector injector = new SpigotInjector(null, null, "packet_handler", false);
        EmbeddedChannel channel = new EmbeddedChannel();

        assertTrue(injector.trackPassthroughClient(channel));

        channel.close().syncUninterruptibly();

        assertFalse(injector.removeInjectedClient(channel));
    }
}
