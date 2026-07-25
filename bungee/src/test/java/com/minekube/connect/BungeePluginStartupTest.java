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
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.packet.PacketHandlers;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityEnforcer;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.inject.CommonPlatformInjector;
import com.minekube.connect.inject.bungee.BungeeInjector;
import com.minekube.connect.listener.BungeeListener;
import com.minekube.connect.listener.BungeeListenerRegistration;
import com.minekube.connect.module.ProxyCommonModule;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.pluginmessage.BungeeSkinApplier;
import com.minekube.connect.startup.StartupGraphProvisioning;
import com.minekube.connect.util.BungeeCommandUtil;
import com.minekube.connect.util.BungeePlatformUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-platform startup / smoke test for the BungeeCord plugin.
 *
 * <p>Asserts the Bungee plugin DI graph — the {@code ProxyCommonModule} + {@code BungeePlatformModule}
 * graph {@link BungeePlugin#onLoad()} builds — including the concrete platform/listener bindings —
 * is provisionable under Velocity 4-class Guice 7 and
 * that a real Guice injector wires the shared graph up cleanly with no {@code ProvisionException}.
 * Like every platform's injector it carries the Bedrock identity graph, so it fails on the pre-fix
 * commit (javax-annotated Bedrock providers) and passes on the fix — see {@link
 * StartupGraphProvisioning}.
 *
 * <p><b>Test limitation.</b> {@code BungeePlugin} extends Bungee's {@code Plugin} and relies on
 * server-side {@code init(...)} for its data folder/proxy, so the real-provision half wires the
 * Bungee proxy graph ({@code ProxyCommonModule}) with the platform-supplied bindings stubbed rather
 * than booting a BungeeCord proxy. That still provisions the full Connect object graph a DI
 * regression would break; the Guice-7 provisionability check additionally covers the Bungee-specific
 * DI classes ({@code BungeeListener}, …).
 */
class BungeePluginStartupTest {
    @TempDir Path tempDir;

    private static List<Class<?>> bungeeGraphRoots() {
        List<Class<?>> roots = new ArrayList<>(StartupGraphProvisioning.coreRuntimeGraphRoots());
        roots.add(BungeeListener.class);
        roots.add(BungeeCommandUtil.class);
        roots.add(BungeePlatformUtils.class);
        roots.add(BungeeInjector.class);
        roots.add(BungeeSkinApplier.class);
        roots.add(BungeeListenerRegistration.class);
        return roots;
    }

    @Test
    void bungeePluginGraphIsProvisionableUnderGuice7() {
        Set<Class<?>> graph = StartupGraphProvisioning.reachableInjectedTypes(bungeeGraphRoots());

        List<String> violations = StartupGraphProvisioning.guice7ProvisioningViolations(graph);

        assertTrue(violations.isEmpty(),
                "Bungee plugin DI classes are not provisionable under Velocity 4.0.0's Guice 7:\n"
                        + String.join("\n", violations));
        assertTrue(graph.contains(BedrockIdentityKeyProvider.class),
                "walk must reach BedrockIdentityKeyProvider (the class that failed on Velocity 4)");
        assertTrue(graph.contains(BungeeListener.class),
                "walk must include member-injected BungeeListener");
    }

    /**
     * Real Guice provisioning of the Bungee proxy graph ({@code ProxyCommonModule}) with the
     * platform-supplied bindings stubbed. Proves the modules wire up and the Bedrock/config graph
     * resolves without a provisioning error. Config load is not driven (it makes a network call);
     * the Guice-7 check above covers the parts not instantiated here.
     */
    @Test
    void bungeeProxyGraphProvisionsKeySingletonsWithoutError() {
        Injector injector = Guice.createInjector(
                new ProxyCommonModule(tempDir),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ConnectLogger.class).toInstance(mock(ConnectLogger.class));
                        bind(PlatformUtils.class).toInstance(mock(PlatformUtils.class));
                        bind(CommonPlatformInjector.class)
                                .toInstance(mock(CommonPlatformInjector.class));
                        bind(String.class).annotatedWith(Names.named("platformName"))
                                .toInstance("BungeeCord");
                    }
                });

        assertDoesNotThrow(() -> injector.getInstance(ConnectApi.class));
        assertDoesNotThrow(() -> injector.getInstance(PacketHandlers.class));
        assertDoesNotThrow(() -> injector.getInstance(BedrockAdmissionCoordinator.class));
        assertDoesNotThrow(() -> injector.getInstance(BedrockIdentityEnforcer.class));
        assertDoesNotThrow(() -> injector.getInstance(BedrockIdentityKeyProvider.class));
    }
}
