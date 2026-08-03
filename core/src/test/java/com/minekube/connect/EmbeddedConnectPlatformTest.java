package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.inject.Injector;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.packet.PacketHandlers;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.inject.CommonPlatformInjector;
import com.minekube.connect.module.PostInitializeModule;
import com.minekube.connect.module.WatcherModule;
import com.minekube.connect.register.WatchHealthServer;
import com.minekube.connect.register.WatcherRegister;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class EmbeddedConnectPlatformTest {
    @TempDir
    Path tempDir;

    @Test
    void embeddedConfigUsesExplicitEndpointAndOfflineCompatibility() {
        ConnectConfig config = ConnectConfig.embedded("amber-fox", true);

        assertEquals("amber-fox", config.getEndpoint());
        assertEquals(Boolean.TRUE, config.getAllowOfflineModePlayers());
        assertTrue(config.getMetrics().isDisabled());
    }

    @Test
    void embeddedInitializationDoesNotLoadOrCreateConfigFile() {
        Fixture fixture = fixture(true);
        Path dataDirectory = tempDir.resolve("share");
        ConnectConfig config = ConnectConfig.embedded("amber-fox", true);
        ConfigHolder configHolder = new ConfigHolder();
        PacketHandlers packetHandlers = mock(PacketHandlers.class);

        fixture.platform.initEmbedded(dataDirectory, config, configHolder, packetHandlers);

        assertTrue(Files.isDirectory(dataDirectory));
        assertFalse(Files.exists(dataDirectory.resolve("config.yml")));
        assertSame(config, configHolder.get());
    }

    @Test
    void watcherModulesAreInstalledOnlyAfterPlatformInjectionSucceeds() throws Exception {
        Fixture failed = fixture(false);
        failed.platform.initEmbedded(
                tempDir.resolve("failed"),
                ConnectConfig.embedded("amber-fox", true),
                new ConfigHolder(),
                mock(PacketHandlers.class));

        assertFalse(failed.platform.enable(new WatcherModule()));
        assertTrue(failed.platform.disable());

        verify(failed.configInjector, never())
                .createChildInjector(any(PostInitializeModule.class));
        verify(failed.watcher, never()).start();
        verify(failed.watcher, never()).stop();

        Fixture successful = fixture(true);
        successful.platform.initEmbedded(
                tempDir.resolve("successful"),
                ConnectConfig.embedded("amber-fox", true),
                new ConfigHolder(),
                mock(PacketHandlers.class));
        doAnswer(invocation -> {
            successful.watcher.start();
            return successful.enabledInjector;
        }).when(successful.configInjector)
                .createChildInjector(any(PostInitializeModule.class));

        assertTrue(successful.platform.enable(new WatcherModule()));

        InOrder order = inOrder(successful.platformInjector, successful.watcher);
        order.verify(successful.platformInjector).inject();
        order.verify(successful.watcher).start();
    }

    @Test
    void embeddedDisableClosesEveryRuntimeComponentExactlyOnce() {
        Fixture fixture = fixture(true);
        fixture.platform.initEmbedded(
                tempDir.resolve("disable"),
                ConnectConfig.embedded("amber-fox", true),
                new ConfigHolder(),
                mock(PacketHandlers.class));
        assertTrue(fixture.platform.enable(new WatcherModule()));

        assertTrue(fixture.platform.disable());
        assertTrue(fixture.platform.disable());

        verify(fixture.libp2p, times(1)).stop();
        verify(fixture.healthServer, times(1)).stop();
        verify(fixture.watcher, times(1)).stop();
        verify(fixture.tunneler, times(1)).close();
        verify(fixture.platformInjector, times(1)).shutdown();
    }

    private Fixture fixture(boolean injectionSucceeds) {
        ConnectApi api = mock(ConnectApi.class);
        CommonPlatformInjector platformInjector = mock(CommonPlatformInjector.class);
        ConnectLogger logger = mock(ConnectLogger.class);
        Injector parentInjector = mock(Injector.class);
        Injector configInjector = mock(Injector.class);
        Injector enabledInjector = mock(Injector.class);
        BedrockAdmissionCoordinator admissionCoordinator =
                new BedrockAdmissionCoordinator(new VerifiedBedrockIdentityRegistry());
        WatcherRegister watcher = mock(WatcherRegister.class);
        WatchHealthServer healthServer = mock(WatchHealthServer.class);
        Libp2pEndpoint libp2p = mock(Libp2pEndpoint.class);
        Tunneler tunneler = mock(Tunneler.class);

        try {
            when(platformInjector.inject()).thenReturn(injectionSucceeds);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(parentInjector.createChildInjector(any(com.google.inject.Module.class)))
                .thenReturn(configInjector);
        when(configInjector.createChildInjector(any(PostInitializeModule.class)))
                .thenReturn(enabledInjector);
        for (Injector injector : new Injector[]{configInjector, enabledInjector}) {
            when(injector.getInstance(Libp2pEndpoint.class)).thenReturn(libp2p);
            when(injector.getInstance(WatchHealthServer.class)).thenReturn(healthServer);
            when(injector.getInstance(WatcherRegister.class)).thenReturn(watcher);
            when(injector.getInstance(Tunneler.class)).thenReturn(tunneler);
            when(injector.getInstance(CommonPlatformInjector.class)).thenReturn(platformInjector);
        }

        ConnectPlatform platform = new ConnectPlatform(
                api,
                platformInjector,
                logger,
                parentInjector,
                admissionCoordinator);
        return new Fixture(
                platform,
                platformInjector,
                configInjector,
                enabledInjector,
                watcher,
                healthServer,
                libp2p,
                tunneler,
                admissionCoordinator);
    }

    private static final class Fixture {
        private final ConnectPlatform platform;
        private final CommonPlatformInjector platformInjector;
        private final Injector configInjector;
        private final Injector enabledInjector;
        private final WatcherRegister watcher;
        private final WatchHealthServer healthServer;
        private final Libp2pEndpoint libp2p;
        private final Tunneler tunneler;
        private final BedrockAdmissionCoordinator admissionCoordinator;

        private Fixture(
                ConnectPlatform platform,
                CommonPlatformInjector platformInjector,
                Injector configInjector,
                Injector enabledInjector,
                WatcherRegister watcher,
                WatchHealthServer healthServer,
                Libp2pEndpoint libp2p,
                Tunneler tunneler,
                BedrockAdmissionCoordinator admissionCoordinator
        ) {
            this.platform = platform;
            this.platformInjector = platformInjector;
            this.configInjector = configInjector;
            this.enabledInjector = enabledInjector;
            this.watcher = watcher;
            this.healthServer = healthServer;
            this.libp2p = libp2p;
            this.tunneler = tunneler;
            this.admissionCoordinator = admissionCoordinator;
        }
    }
}
