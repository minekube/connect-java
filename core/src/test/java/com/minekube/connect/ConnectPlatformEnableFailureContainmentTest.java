package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.inject.Injector;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import org.junit.jupiter.api.Test;

/**
 * Guards that a failing platform injector stays a contained, logged failure instead of taking the
 * whole plugin down.
 *
 * <p>Platform injectors reflect against server and plugin internals (NMS, ViaVersion, ProtocolLib),
 * so signature drift arrives as an {@link Error} - the ViaVersion 5.x
 * {@code BukkitChannelInitializer.getOriginal()} removal produced a {@link NoSuchMethodError} (see
 * {@code SpigotInjectorViaLegacyPathTest}). {@code enable()} used to catch only {@link Exception},
 * so that {@code Error} escaped {@code onEnable()} and the platform disabled the plugin outright.
 */
class ConnectPlatformEnableFailureContainmentTest {

    @Test
    void enableContainsAnInjectorError() throws Exception {
        PlatformInjector injector = mock(PlatformInjector.class);
        NoSuchMethodError error = new NoSuchMethodError(
                "io.netty.channel.ChannelInitializer "
                        + "com.viaversion.viaversion.bukkit.handlers.BukkitChannelInitializer"
                        + ".getOriginal()");
        when(injector.inject()).thenThrow(error);
        ConnectLogger logger = mock(ConnectLogger.class);

        assertFalse(platform(injector, logger).enable(),
                "an injector Error must be reported as a failed injection, not propagate out of "
                        + "enable() and get the plugin disabled by the platform");
        verify(logger).error("Failed to inject the packet listener!", error);
    }

    /** An injector that merely returns false is still reported the same way. */
    @Test
    void enableReportsAFailedInjection() throws Exception {
        PlatformInjector injector = mock(PlatformInjector.class);
        when(injector.inject()).thenReturn(false);

        assertFalse(platform(injector, mock(ConnectLogger.class)).enable());
    }

    private static ConnectPlatform platform(PlatformInjector injector, ConnectLogger logger) {
        // No admission coordinator: enable() never touches it, and a real one would leak its
        // cleanup executor because these tests never reach disable().
        return new ConnectPlatform(
                mock(ConnectApi.class), injector, logger, mock(Injector.class), null);
    }
}
