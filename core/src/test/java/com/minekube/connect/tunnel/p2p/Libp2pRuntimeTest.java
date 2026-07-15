/*
 * Copyright (c) 2021-2022 Minekube. https://minekube.com
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
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.bedrock.BedrockIdentityReadiness;
import com.minekube.connect.bedrock.BedrockIdentityReadiness.Transport;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.platform.util.PlatformUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import minekube.connect.v1alpha1.ConnectLibp2P.OfflineMode;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerCapacity;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterResult;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Libp2pRuntimeTest {
    @TempDir Path tempDir;

    @Test
    void resolvesJvmLibp2pHostClass() {
        assertEquals(11, Libp2pRuntime.minimumJavaFeatureVersion());
        assertEquals("io.libp2p.core.Host", Libp2pRuntime.hostClassName());
    }

    @Test
    void readinessTransitionClosesTheActiveRegistrationForReregistration() throws Exception {
        ConnectConfig config = identityConfig();
        BedrockIdentityReadiness readiness = new BedrockIdentityReadiness(
                config,
                new BedrockIdentityKeyProvider(config, new OkHttpClient()));
        readiness.observe(Transport.LIBP2P);
        Libp2pEndpointRuntime runtime = new Libp2pEndpointRuntime(
                Path.of("."),
                config,
                "token",
                mock(PlatformUtils.class),
                mock(ConnectLogger.class),
                mock(PlatformInjector.class),
                mock(SimpleConnectApi.class),
                readiness);
        PeerRegistrationClient client = new PeerRegistrationClient(new PeerRegistrationHandshake(
                EndpointPeerIdentity.loadOrCreate(tempDir.resolve("libp2p-identity.key")),
                "endpoint",
                "token",
                "instance",
                List.of(),
                OfflineMode.OFFLINE_MODE_ALLOWED,
                List.of(),
                PeerCapacity.getDefaultInstance()));
        setActiveRegistration(runtime, client);

        invokeRefreshRegistrationReadiness(runtime);
        assertFalse(client.closedFuture().isDone());

        setField(config.getBedrockIdentity(), "enforcement", "disabled");
        invokeRefreshRegistrationReadiness(runtime);

        assertTrue(client.closedFuture().isDone());
    }

    private static ConnectConfig identityConfig() throws Exception {
        ConnectConfig config = new ConnectConfig();
        setField(config.getBedrockIdentity(), "enforcement", "require");
        setField(config.getBedrockIdentity(), "publicKey",
                Base64.getEncoder().encodeToString(new byte[32]));
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setActiveRegistration(
            Libp2pEndpointRuntime runtime,
            PeerRegistrationClient client) throws Exception {
        Class<?> registrationClass = Class.forName(
                Libp2pEndpointRuntime.class.getName() + "$ActiveRegistration");
        Constructor<?> constructor = registrationClass.getDeclaredConstructor(
                PeerRegistrationClient.class,
                PeerRegisterResult.class);
        constructor.setAccessible(true);
        Object registration = constructor.newInstance(client, PeerRegisterResult.getDefaultInstance());
        Field field = Libp2pEndpointRuntime.class.getDeclaredField("activeRegistration");
        field.setAccessible(true);
        field.set(runtime, registration);
    }

    private static void invokeRefreshRegistrationReadiness(Libp2pEndpointRuntime runtime)
            throws Exception {
        Method method = Libp2pEndpointRuntime.class.getDeclaredMethod("refreshRegistrationReadiness");
        method.setAccessible(true);
        method.invoke(runtime);
    }
}
