package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.inject.Injector;
import com.minekube.connect.ConnectPlatform;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.inject.CommonPlatformInjector;
import com.minekube.connect.register.WatchHealthServer;
import com.minekube.connect.register.WatcherRegister;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class ConnectPlatformBedrockIdentityLifecycleTest {
    @Test
    void fourArgumentConstructorUsesInjectorRegistryAndClosesItOnDisable() {
        ScheduledThreadPoolExecutor cleanupExecutor = new ScheduledThreadPoolExecutor(1);
        cleanupExecutor.setRemoveOnCancelPolicy(true);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry(cleanupExecutor);
        registry.stage(VerifiedBedrockIdentityRegistryTest.session(false));
        Injector guice = mock(Injector.class);
        when(guice.getInstance(VerifiedBedrockIdentityRegistry.class)).thenReturn(registry);
        when(guice.getInstance(Libp2pEndpoint.class)).thenReturn(mock(Libp2pEndpoint.class));
        when(guice.getInstance(WatchHealthServer.class)).thenReturn(mock(WatchHealthServer.class));
        when(guice.getInstance(WatcherRegister.class)).thenReturn(mock(WatcherRegister.class));
        when(guice.getInstance(Tunneler.class)).thenReturn(mock(Tunneler.class));
        when(guice.getInstance(CommonPlatformInjector.class)).thenReturn(mock(CommonPlatformInjector.class));
        ConnectPlatform platform = new ConnectPlatform(
                mock(ConnectApi.class),
                mock(PlatformInjector.class),
                mock(ConnectLogger.class),
                guice);

        assertTrue(platform.disable());

        assertTrue(cleanupExecutor.isShutdown());
        assertTrue(cleanupExecutor.getQueue().isEmpty());
    }
}
