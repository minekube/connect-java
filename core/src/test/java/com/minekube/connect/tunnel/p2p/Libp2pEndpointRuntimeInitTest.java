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
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.logger.ConnectLogger;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the reflective boundary between {@link Libp2pEndpoint} (parent class
 * loader) and {@code Libp2pEndpointRuntime} (isolated child-first class loader).
 *
 * <p>The endpoint wrapper resolves the runtime constructor reflectively via
 * {@code getDeclaredConstructor(...)}. If the wrapper's parameter list drifts
 * from the runtime's actual constructor — as it did when PR #57 added the
 * trusted-Bedrock-identity dependencies to the runtime but not to the wrapper —
 * the lookup throws {@link NoSuchMethodException}, the wrapper silently sets its
 * runtime to {@code null}, logs "Failed to initialize Connect libp2p endpoint
 * runtime", and every player join later fails with "No available Browser Hub".
 *
 * <p>This drift is not tied to any JDK version: the reflective lookup either
 * matches an existing constructor or it does not, identically on Java 11, 21 and
 * 26. Constructing the wrapper and asserting the runtime is non-null keeps the
 * two constructor signatures in lock-step.
 */
class Libp2pEndpointRuntimeInitTest {

    @Test
    void initializesRuntimeAcrossIsolatedLoaderBoundary(@TempDir Path dataDirectory) throws Exception {
        ConnectLogger logger = mock(ConnectLogger.class);

        // Only the dependency *types* drive the reflective constructor lookup, so
        // null values for the isolated runtime's collaborators are sufficient to
        // exercise resolution + construction across the class-loader boundary.
        Libp2pEndpoint endpoint = new Libp2pEndpoint(
                dataDirectory,
                null,   // ConnectConfig
                "connect-token",
                null,   // PlatformUtils
                logger,
                null,   // PlatformInjector
                null,   // SimpleConnectApi
                null,   // BedrockIdentityReadiness
                null);  // BedrockAdmissionCoordinator

        Field runtimeField = Libp2pEndpoint.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        Object runtime = runtimeField.get(endpoint);

        assertNotNull(runtime,
                "Libp2pEndpoint must resolve and construct the isolated Libp2pEndpointRuntime; "
                        + "a null runtime means the reflective constructor lookup failed "
                        + "(NoSuchMethodException) and the libp2p endpoint will never start.");
    }
}
