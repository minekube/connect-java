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
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.packet.PacketHandlers;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.BedrockIdentityEnforcer;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.inject.velocity.VelocityInjector;
import com.minekube.connect.listener.VelocityListener;
import com.minekube.connect.listener.VelocityListenerRegistration;
import com.minekube.connect.module.ProxyCommonModule;
import com.minekube.connect.module.VelocityPlatformModule;
import com.minekube.connect.startup.StartupGraphProvisioning;
import com.minekube.connect.util.VelocityCommandUtil;
import com.minekube.connect.util.VelocityPlatformUtils;
import com.minekube.connect.util.VelocitySkinApplier;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

/**
 * Per-platform startup / smoke test for the Velocity plugin.
 *
 * <p>This is the direct regression guard for the Velocity 4.0.0 "Cant create plugin connect"
 * failure: it asserts the whole Velocity plugin DI graph — {@code ProxyCommonModule} +
 * {@code VelocityPlatformModule}, the exact modules {@link VelocityPlugin} builds — is provisionable
 * under Velocity 4's Guice 7, and that a real Guice injector wires the graph up cleanly with no
 * {@code ProvisionException}/{@code CreationException}.
 *
 * <p><b>Fails before the fix, passes after.</b> {@link #velocityPluginGraphIsProvisionableUnderGuice7()}
 * fails on the pre-fix commit (the Bedrock providers were annotated with {@code javax.inject}, which
 * Guice 7 ignores) and passes on the fixed code. See {@link StartupGraphProvisioning} for why a
 * Guice 7 rule replica is used rather than a live Guice 7 injector (this repo builds against Guice
 * 6, which accepts both annotation packages and so cannot reproduce the failure through a real
 * provision).
 */
class VelocityPluginStartupTest {
    @TempDir Path tempDir;

    /** Velocity module bindings and providers, on top of the shared core graph. */
    private static List<Class<?>> velocityGraphRoots() {
        List<Class<?>> roots = new ArrayList<>(StartupGraphProvisioning.coreRuntimeGraphRoots());
        roots.add(VelocityPlugin.class);
        roots.add(VelocityListener.class);
        roots.add(VelocityCommandUtil.class);
        roots.add(VelocityPlatformUtils.class);
        roots.add(VelocityInjector.class);
        roots.add(VelocitySkinApplier.class);
        roots.add(VelocityListenerRegistration.class);
        return roots;
    }

    /**
     * The reintroduction-detector: every {@code com.minekube.connect} class Guice would construct on
     * the Velocity injector must be provisionable under Guice 7. This is the assertion that fails on
     * the pre-fix code and passes on the fix.
     */
    @Test
    void velocityPluginGraphIsProvisionableUnderGuice7() {
        Set<Class<?>> graph =
                StartupGraphProvisioning.reachableInjectedTypes(velocityGraphRoots());

        List<String> violations = StartupGraphProvisioning.guice7ProvisioningViolations(graph);

        assertTrue(violations.isEmpty(),
                "Velocity plugin DI classes are not provisionable under Velocity 4.0.0's Guice 7:\n"
                        + String.join("\n", violations));
        // Guard the guard: the walk must actually reach the class that regressed.
        assertTrue(graph.contains(BedrockIdentityKeyProvider.class),
                "walk must reach BedrockIdentityKeyProvider (the class that failed on Velocity 4)");
        assertTrue(graph.contains(VelocityListener.class),
                "walk must include member-injected VelocityListener");
    }

    /**
     * Real Guice provisioning of the actual Velocity module graph. Boots the same child injector
     * {@link VelocityPlugin} constructs ({@code ProxyCommonModule} + {@code VelocityPlatformModule})
     * and resolves the plugin's key singletons, proving the graph wires up with no provisioning
     * error. {@code ConnectPlatform.init()} (config load) is intentionally not driven — it performs
     * a network call to generate an endpoint name — so the hermetic singletons are resolved
     * directly; the Guice-7 check above covers the rest of the graph.
     */
    @Test
    void velocityModuleGraphProvisionsKeySingletonsWithoutError() {
        Injector parent = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(ProxyServer.class).toInstance(mock(ProxyServer.class));
                bind(EventManager.class).toInstance(mock(EventManager.class));
                bind(Logger.class).toInstance(mock(Logger.class));
                // Lets Guice just-in-time bind VelocityPlugin (needed by the listener-registration
                // provider) without constructing it — construction would run ConnectPlatform.init(),
                // which makes a network call. We never request VelocityPlugin here.
                bind(Path.class).annotatedWith(DataDirectory.class).toInstance(tempDir);
            }
        });

        Injector child = parent.createChildInjector(
                new ProxyCommonModule(tempDir),
                new VelocityPlatformModule(parent));

        assertDoesNotThrow(() -> child.getInstance(ConnectLogger.class));
        assertDoesNotThrow(() -> child.getInstance(ConnectApi.class));
        assertDoesNotThrow(() -> child.getInstance(PlatformInjector.class));
        assertDoesNotThrow(() -> child.getInstance(PacketHandlers.class));
        assertDoesNotThrow(() -> child.getInstance(BedrockAdmissionCoordinator.class));
        assertDoesNotThrow(() -> child.getInstance(BedrockIdentityEnforcer.class));
        assertDoesNotThrow(() -> child.getInstance(BedrockIdentityKeyProvider.class));
    }
}
