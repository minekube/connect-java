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

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.platform.util.PlatformUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

public final class Libp2pEndpoint {
    static final String REGISTER_PROTOCOL_ID = "/minekube/connect/register/1.0.0";

    private final ConnectLogger logger;
    private Object runtime;
    private Method startMethod;
    private Method stopMethod;

    @Inject
    public Libp2pEndpoint(
            @Named("dataDirectory") Path dataDirectory,
            ConnectConfig connectConfig,
            @Named("connectToken") String connectToken,
            PlatformUtils platformUtils,
            ConnectLogger logger,
            PlatformInjector platformInjector,
            SimpleConnectApi api) {
        this.logger = logger;
        try {
            Class<?> runtimeClass = Class.forName(
                    "com.minekube.connect.tunnel.p2p.Libp2pEndpointRuntime",
                    true,
                    Libp2pRuntimeLoader.classLoader());
            Constructor<?> constructor = runtimeClass.getDeclaredConstructor(
                    Path.class,
                    ConnectConfig.class,
                    String.class,
                    PlatformUtils.class,
                    ConnectLogger.class,
                    PlatformInjector.class,
                    SimpleConnectApi.class);
            constructor.setAccessible(true);
            this.runtime = constructor.newInstance(
                    dataDirectory,
                    connectConfig,
                    connectToken,
                    platformUtils,
                    logger,
                    platformInjector,
                    api);
            this.startMethod = runtimeClass.getDeclaredMethod("start");
            this.stopMethod = runtimeClass.getDeclaredMethod("stop");
            this.startMethod.setAccessible(true);
            this.stopMethod.setAccessible(true);
        } catch (Exception | LinkageError e) {
            this.runtime = null;
            logger.error("Failed to initialize Connect libp2p endpoint runtime", e);
        }
    }

    @Inject
    public synchronized void start() {
        if (runtime == null) {
            return;
        }
        try {
            startMethod.invoke(runtime);
        } catch (InvocationTargetException e) {
            stop();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            logger.error("Failed to start Connect libp2p endpoint", cause);
        } catch (Exception | LinkageError e) {
            stop();
            logger.error("Failed to start Connect libp2p endpoint", e);
        }
    }

    public synchronized void stop() {
        if (runtime != null) {
            try {
                stopMethod.invoke(runtime);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                logger.error("Failed to stop Connect libp2p endpoint", cause);
            } catch (Exception | LinkageError e) {
                logger.error("Failed to stop Connect libp2p endpoint", e);
            }
        }
    }
}
