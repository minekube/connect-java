package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.inject.Injector;
import com.google.inject.Module;
import com.minekube.connect.api.logger.ConnectLogger;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VelocityPluginTest {
    @Test
    void proxyShutdownDisablesConnectPlatform() throws Exception {
        ConnectPlatform platform = mock(ConnectPlatform.class);
        Injector parentInjector = mock(Injector.class);
        Injector childInjector = mock(Injector.class);
        when(parentInjector.createChildInjector(any(Module[].class))).thenReturn(childInjector);
        when(childInjector.getInstance(ConnectPlatform.class)).thenReturn(platform);
        when(childInjector.getInstance(ConnectLogger.class)).thenReturn(mock(ConnectLogger.class));
        VelocityPlugin plugin = new VelocityPlugin(Path.of("."), parentInjector);

        Method shutdownHandler = assertDoesNotThrow(() ->
                VelocityPlugin.class.getMethod("onProxyShutdown", ProxyShutdownEvent.class));
        assertNotNull(shutdownHandler.getAnnotation(Subscribe.class));
        shutdownHandler.invoke(plugin, new ProxyShutdownEvent());

        verify(platform).disable();
    }
}
