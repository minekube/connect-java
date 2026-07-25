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

package com.minekube.connect.startup;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.inject.CommonPlatformInjector;
import com.minekube.connect.module.ServerCommonModule;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.register.WatcherRegister;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup smoke test for the shared Connect plugin object graph — the portion of the DI graph every
 * platform (Velocity/Spigot/Bungee) provisions identically. It is the workhorse behind the
 * per-platform startup tests: those reuse {@link StartupGraphProvisioning#coreRuntimeGraphRoots()}
 * and add their platform-specific roots on top.
 *
 * <p>This is the captain-directed prevention for the class of DI/startup regression that produced
 * the Velocity 4.0.0 {@code BedrockIdentityKeyProvider} "Cant create plugin connect" failure. It
 * fails on the pre-fix code (which annotated the Bedrock providers with {@code javax.inject},
 * invisible to Velocity 4's Guice 7) and passes on the fixed code. See
 * {@link StartupGraphProvisioning} for why a Guice 7 rule replica is used instead of a live Guice 7
 * injector, and {@code BedrockVelocityGuice7ProvisioningTest} for the original narrower guard.
 *
 * <p>The reflective-constructor regression class (the Java-26 {@code Libp2pEndpointRuntime}
 * constructor arity mismatch) is guarded by {@code Libp2pEndpointRuntimeInitTest} and
 * {@code Libp2pRuntimeBoundaryTest}, which run in this same module's test task; {@code Libp2pEndpoint}
 * and {@code Libp2pTunnelTransport} are also part of the graph asserted here.
 */
class PluginGraphStartupTest {
    @TempDir Path tempDir;

    /**
     * The whole shared runtime graph must be provisionable under Velocity-4-class Guice 7. This is
     * the assertion that fails on the pre-fix commit (javax-annotated Bedrock providers) and passes
     * on the fixed code.
     */
    @Test
    void sharedRuntimeGraphIsProvisionableUnderGuice7() {
        Set<Class<?>> graph =
                StartupGraphProvisioning.reachableInjectedTypes(
                        StartupGraphProvisioning.coreRuntimeGraphRoots());

        List<String> violations = StartupGraphProvisioning.guice7ProvisioningViolations(graph);

        assertTrue(violations.isEmpty(),
                "Connect DI classes on the shared plugin graph are not provisionable under "
                        + "Velocity 4.0.0's Guice 7:\n" + String.join("\n", violations));
    }

    /**
     * Guards the guard: the reflective walk must actually reach the class that regressed
     * ({@code BedrockIdentityKeyProvider}) and the rest of the Bedrock identity graph, otherwise
     * {@link #sharedRuntimeGraphIsProvisionableUnderGuice7()} could pass vacuously by covering
     * nothing.
     */
    @Test
    void reflectiveWalkReachesTheClassesThatRegressed() {
        Set<Class<?>> graph =
                StartupGraphProvisioning.reachableInjectedTypes(
                        StartupGraphProvisioning.coreRuntimeGraphRoots());

        assertTrue(graph.contains(BedrockIdentityKeyProvider.class),
                "walk must reach BedrockIdentityKeyProvider (the class that failed on Velocity 4)");
        assertTrue(graph.contains(BedrockAdmissionCoordinator.class),
                "walk must reach BedrockAdmissionCoordinator");
        assertTrue(graph.contains(BedrockIdentityEnforcer.class),
                "walk must reach BedrockIdentityEnforcer");
        assertTrue(graph.contains(WatcherRegister.class),
                "walk must include member-injected WatcherRegister");
    }

    /**
     * Real Guice-6 provisioning of the hermetic slice of the server graph: proves the modules wire
     * up and the Bedrock/config graph resolves without a {@code ProvisionException}. Config loading
     * ({@code ConnectPlatform.init()}) is intentionally not driven here because it performs a
     * network call to generate an endpoint name; the reflective Guice-7 check above covers the parts
     * of the graph this real provision does not instantiate.
     */
    @Test
    void serverGraphProvisionsKeySingletonsWithoutError() {
        Injector injector = Guice.createInjector(
                new ServerCommonModule(tempDir),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        // Bindings the platform module would normally supply.
                        bind(ConnectLogger.class).toInstance(mock(ConnectLogger.class));
                        bind(PlatformUtils.class).toInstance(mock(PlatformUtils.class));
                        bind(CommonPlatformInjector.class)
                                .toInstance(mock(CommonPlatformInjector.class));
                        bind(String.class).annotatedWith(Names.named("platformName"))
                                .toInstance("test");
                    }
                });

        assertNotNull(injector.getInstance(ConnectApi.class));
        assertNotNull(injector.getInstance(ConfigHolder.class));
        assertNotNull(injector.getInstance(BedrockAdmissionCoordinator.class));
        assertNotNull(injector.getInstance(BedrockIdentityEnforcer.class));
        assertNotNull(injector.getInstance(BedrockIdentityKeyProvider.class));
        assertNotNull(injector.getInstance(PacketHandlers.class));
    }
}
