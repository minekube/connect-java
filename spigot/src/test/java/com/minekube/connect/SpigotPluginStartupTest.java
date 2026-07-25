/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.minekube.connect.addon.AddonManagerAddon;
import com.minekube.connect.addon.DebugAddon;
import com.minekube.connect.addon.PacketHandlerAddon;
import com.minekube.connect.addon.data.SpigotDataAddon;
import com.minekube.connect.addon.data.SpigotDataHandler;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.packet.PacketHandlers;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityEnforcer;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.inject.CommonPlatformInjector;
import com.minekube.connect.inject.spigot.SpigotInjector;
import com.minekube.connect.listener.PaperProfileListener;
import com.minekube.connect.listener.SpigotListener;
import com.minekube.connect.listener.SpigotListenerRegistration;
import com.minekube.connect.module.ServerCommonModule;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.startup.StartupGraphProvisioning;
import com.minekube.connect.util.SpigotCommandUtil;
import com.minekube.connect.util.SpigotPlatformUtils;
import com.minekube.connect.util.SpigotVersionSpecificMethods;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-platform startup / smoke test for the Bukkit/Spigot/Paper plugin.
 *
 * <p>Asserts the Spigot plugin DI graph — the {@code ServerCommonModule} + {@code SpigotPlatform}
 * graph {@link SpigotPlugin#onLoad()} builds — including the concrete platform/listener/addon
 * bindings — is provisionable under Velocity 4-class Guice 7 and
 * that a real Guice injector wires the shared graph up cleanly with no {@code ProvisionException}.
 * Like every platform's injector it carries the Bedrock identity graph, so it fails on the pre-fix
 * commit (javax-annotated Bedrock providers) and passes on the fix — see {@link
 * StartupGraphProvisioning}.
 *
 * <p><b>Test limitation.</b> {@code SpigotPlugin} extends Bukkit's {@code JavaPlugin} and cannot be
 * instantiated outside a running server, so the real-provision half wires the Spigot server graph
 * ({@code ServerCommonModule}) with the platform-supplied bindings stubbed, rather than booting a
 * Bukkit server. That still provisions the full Connect object graph a DI regression would break;
 * the Guice-7 provisionability check additionally covers the Spigot-specific DI classes
 * ({@code SpigotPlatform}, {@code SpigotDataAddon}, …).
 */
class SpigotPluginStartupTest {
    @TempDir Path tempDir;

    private static List<Class<?>> spigotGraphRoots() {
        List<Class<?>> roots = new ArrayList<>(StartupGraphProvisioning.coreRuntimeGraphRoots());
        roots.add(SpigotPlatform.class);
        roots.add(SpigotDataAddon.class);
        roots.add(AddonManagerAddon.class);
        roots.add(DebugAddon.class);
        roots.add(PacketHandlerAddon.class);
        roots.add(SpigotCommandUtil.class);
        roots.add(SpigotPlatformUtils.class);
        roots.add(SpigotInjector.class);
        roots.add(SpigotVersionSpecificMethods.class);
        roots.add(SpigotListenerRegistration.class);
        roots.add(SpigotListener.class);
        roots.add(PaperProfileListener.class);
        roots.add(SpigotDataHandler.class);
        return roots;
    }

    @Test
    void spigotPluginGraphIsProvisionableUnderGuice7() {
        Set<Class<?>> graph = StartupGraphProvisioning.reachableInjectedTypes(spigotGraphRoots());

        List<String> violations = StartupGraphProvisioning.guice7ProvisioningViolations(graph);

        assertTrue(violations.isEmpty(),
                "Spigot plugin DI classes are not provisionable under Velocity 4.0.0's Guice 7:\n"
                        + String.join("\n", violations));
        assertTrue(graph.contains(BedrockIdentityKeyProvider.class),
                "walk must reach BedrockIdentityKeyProvider (the class that failed on Velocity 4)");
        assertTrue(graph.contains(SpigotDataAddon.class),
                "walk must include member-injected SpigotDataAddon");
        assertTrue(graph.contains(SpigotListener.class),
                "walk must include member-injected SpigotListener");
        assertTrue(graph.contains(PaperProfileListener.class),
                "walk must include member-injected PaperProfileListener");
        assertTrue(graph.contains(AddonManagerAddon.class),
                "walk must include member-injected AddonManagerAddon");
        assertTrue(graph.contains(DebugAddon.class),
                "walk must include member-injected DebugAddon");
        assertTrue(graph.contains(PacketHandlerAddon.class),
                "walk must include member-injected PacketHandlerAddon");
    }

    /**
     * Real Guice provisioning of the Spigot server graph ({@code ServerCommonModule}) with the
     * platform-supplied bindings stubbed. Proves the modules wire up and the Bedrock/config graph
     * resolves without a provisioning error. Config load is not driven (it makes a network call);
     * the Guice-7 check above covers the parts not instantiated here.
     */
    @Test
    void spigotServerGraphProvisionsKeySingletonsWithoutError() {
        Injector injector = Guice.createInjector(
                new ServerCommonModule(tempDir),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ConnectLogger.class).toInstance(mock(ConnectLogger.class));
                        bind(PlatformUtils.class).toInstance(mock(PlatformUtils.class));
                        bind(CommonPlatformInjector.class)
                                .toInstance(mock(CommonPlatformInjector.class));
                        bind(String.class).annotatedWith(Names.named("platformName"))
                                .toInstance("Spigot");
                    }
                });

        assertDoesNotThrow(() -> injector.getInstance(ConnectApi.class));
        assertDoesNotThrow(() -> injector.getInstance(PacketHandlers.class));
        assertDoesNotThrow(() -> injector.getInstance(BedrockAdmissionCoordinator.class));
        assertDoesNotThrow(() -> injector.getInstance(BedrockIdentityEnforcer.class));
        assertDoesNotThrow(() -> injector.getInstance(BedrockIdentityKeyProvider.class));
    }
}
