package com.minekube.connect.inject.spigot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.network.netty.LocalSession;
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

    /**
     * Connect's addons - the login-path data handler and the login-stall watchdog alike - are
     * installed only for sessions whose login Connect owns. A passthrough (offline-mode) session
     * never gets one, so a stall there cannot be attributed by the connector, and the
     * {@code connect-player} channel attribute (never set for passthrough either) cannot be used to
     * exempt such an endpoint.
     */
    @Test
    void passthroughSessionsAreNeverInjected() {
        assertFalse(SpigotInjector.injectsAddons(session(true)));
        assertTrue(SpigotInjector.injectsAddons(session(false)));
    }

    private static LocalSession.Context session(boolean passthrough) {
        ConnectPlayer player = mock(ConnectPlayer.class);
        when(player.getAuth()).thenReturn(new Auth(passthrough));
        LocalSession.Context context = mock(LocalSession.Context.class);
        when(context.getPlayer()).thenReturn(player);
        return context;
    }
}
