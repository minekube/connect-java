package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.config.ProxyConnectConfig;
import com.minekube.connect.module.ConfigLoadedModule;
import com.minekube.connect.module.PostInitializeModule;
import com.minekube.connect.module.ProxyCommonModule;
import com.minekube.connect.module.VelocityPlatformModule;
import com.minekube.connect.module.WatcherModule;
import com.minekube.connect.register.WatchHealthServer;
import com.minekube.connect.register.WatcherRegister;
import com.minekube.connect.watch.WatchClient;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

/** Regression guard for one Bedrock admission coordinator across the Velocity injector chain. */
class VelocityBedrockAdmissionCoordinatorScopeTest {
    @TempDir Path tempDir;

    @Test
    void watcherMintAndStageShareThePlatformCoordinatorAcrossChildInjectors() throws Exception {
        Injector velocityRoot = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(ProxyServer.class).toInstance(mock(ProxyServer.class));
                bind(EventManager.class).toInstance(mock(EventManager.class));
                bind(Logger.class).toInstance(mock(Logger.class));
                bind(Path.class).annotatedWith(DataDirectory.class).toInstance(tempDir);
            }
        });

        // VelocityPlugin constructor: common/platform child, then ConnectPlatform.init()'s config
        // child, then ConnectPlatform.enable()'s post-initialize child.
        Injector platform = velocityRoot.createChildInjector(
                new ProxyCommonModule(tempDir),
                new VelocityPlatformModule(velocityRoot));
        BedrockAdmissionCoordinator platformCoordinator =
                platform.getInstance(BedrockAdmissionCoordinator.class);

        ProxyConnectConfig config = new ProxyConnectConfig();
        platform.getInstance(ConfigHolder.class).set(config);
        Injector configured = platform.createChildInjector(new ConfigLoadedModule(config));

        Module watcherWithoutStartupSideEffects = Modules.override(new WatcherModule())
                .with(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(WatcherRegister.class)
                                .to(NoStartWatcherRegister.class)
                                .asEagerSingleton();
                        bind(WatchHealthServer.class).toInstance(mock(WatchHealthServer.class));
                    }
                });
        Injector enabled = configured.createChildInjector(
                new PostInitializeModule(new Module[] {watcherWithoutStartupSideEffects}));

        WatcherRegister register = enabled.getInstance(WatcherRegister.class);
        WatchClient watchClient =
                (WatchClient) field(register, WatcherRegister.class, "watchClient");
        BedrockAdmissionCoordinator mintCoordinator =
                (BedrockAdmissionCoordinator)
                        field(watchClient, WatchClient.class, "admissionCoordinator");
        BedrockAdmissionCoordinator stageCoordinator =
                (BedrockAdmissionCoordinator)
                        field(register, WatcherRegister.class, "admissionCoordinator");

        Set<BedrockAdmissionCoordinator> coordinators =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(
                coordinators, platformCoordinator, mintCoordinator, stageCoordinator);
        try {
            assertSame(mintCoordinator, stageCoordinator,
                    "WatchClient mint and WatcherRegister/LocalSession stage must share a coordinator");
            assertSame(platformCoordinator, mintCoordinator,
                    "WatchClient must mint admissions on the platform coordinator");
            assertSame(platformCoordinator, stageCoordinator,
                    "WatcherRegister/LocalSession must stage admissions on the platform coordinator");
        } finally {
            coordinators.forEach(BedrockAdmissionCoordinator::close);
        }
    }

    private static Object field(Object target, Class<?> declaringClass, String name)
            throws Exception {
        Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Keeps the real WatcherRegister member-injection graph without opening a WebSocket. */
    public static final class NoStartWatcherRegister extends WatcherRegister {
        @Override
        public synchronized void start() {
            // Deliberately no-op: Guice still injects WatchClient and admissionCoordinator fields.
        }
    }
}
