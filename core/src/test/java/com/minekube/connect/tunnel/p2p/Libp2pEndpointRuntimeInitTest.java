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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the reflective boundary between {@link Libp2pEndpoint} (parent class
 * loader) and {@code Libp2pEndpointRuntime} (isolated child-first class loader).
 *
 * <p>The wrapper's constructor parameter list must match the runtime constructor
 * exactly. Supplying a real admission coordinator also verifies that the
 * injected dependency crosses the boundary unchanged.
 */
class Libp2pEndpointRuntimeInitTest {

    @Test
    void initializesRuntimeAcrossIsolatedLoaderBoundary(
            @TempDir Path dataDirectory) throws Exception {
        ConnectLogger logger = mock(ConnectLogger.class);
        BedrockAdmissionCoordinator admissionCoordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry());

        try {
            Libp2pEndpoint endpoint = new Libp2pEndpoint(
                    dataDirectory,
                    null,   // ConnectConfig
                    "connect-token",
                    null,   // PlatformUtils
                    logger,
                    null,   // PlatformInjector
                    null,   // SimpleConnectApi
                    null,   // BedrockIdentityReadiness
                    admissionCoordinator);

            Field runtimeField = Libp2pEndpoint.class.getDeclaredField("runtime");
            runtimeField.setAccessible(true);
            Object runtime = runtimeField.get(endpoint);

            assertNotNull(runtime,
                    "Libp2pEndpoint must resolve and construct the isolated Libp2pEndpointRuntime; "
                            + "a null runtime means the reflective constructor lookup failed "
                            + "(NoSuchMethodException) and the libp2p endpoint will never start.");

            Field admissionCoordinatorField = runtime.getClass()
                    .getDeclaredField("admissionCoordinator");
            admissionCoordinatorField.setAccessible(true);
            Object runtimeAdmissionCoordinator = admissionCoordinatorField.get(runtime);

            assertNotNull(runtimeAdmissionCoordinator);
            assertSame(admissionCoordinator, runtimeAdmissionCoordinator);
        } finally {
            admissionCoordinator.close();
        }
    }
}
