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
 */

package com.minekube.connect.tunnel.p2p;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Parent-loaded JDK-only facade for the isolated Connect Share libp2p runtime.
 */
public final class DirectP2pNode implements AutoCloseable {
    private Object runtime;
    private Method peerId;
    private Method publicKey;
    private Method startHost;
    private Method sign;
    private Method publish;
    private Method inspect;
    private Method startDiscovery;
    private Method openProxy;
    private Method close;

    public DirectP2pNode() {
        initialize(null);
    }

    public DirectP2pNode(Path identityFile) {
        initialize(Objects.requireNonNull(identityFile, "identityFile"));
    }

    private void initialize(Path identityFile) {
        try {
            Class<?> runtimeClass = Class.forName(
                    "com.minekube.connect.tunnel.p2p.DirectP2pNodeRuntime",
                    true,
                    Libp2pRuntimeLoader.classLoader());
            java.lang.reflect.Constructor<?> constructor = identityFile == null
                    ? runtimeClass.getDeclaredConstructor()
                    : runtimeClass.getDeclaredConstructor(Path.class);
            constructor.setAccessible(true);
            runtime = identityFile == null
                    ? constructor.newInstance()
                    : constructor.newInstance(identityFile);
            peerId = accessible(runtimeClass.getDeclaredMethod("peerId"));
            publicKey = accessible(runtimeClass.getDeclaredMethod("publicKey"));
            startHost = accessible(runtimeClass.getDeclaredMethod(
                    "startHost",
                    DirectP2pHostConfig.class,
                    DirectP2pHostHandler.class));
            sign = accessible(runtimeClass.getDeclaredMethod("sign", byte[].class));
            publish = accessible(runtimeClass.getDeclaredMethod(
                    "publish",
                    String.class));
            inspect = accessible(runtimeClass.getDeclaredMethod(
                    "inspect",
                    String.class,
                    Duration.class));
            startDiscovery = accessible(runtimeClass.getDeclaredMethod(
                    "startDiscovery",
                    DirectP2pDiscoveryListener.class));
            openProxy = accessible(runtimeClass.getDeclaredMethod(
                    "openProxy",
                    String.class,
                    String.class,
                    String.class,
                    DirectP2pAuthMode.class,
                    Duration.class));
            close = accessible(runtimeClass.getDeclaredMethod("close"));
        } catch (Exception | LinkageError e) {
            throw new IllegalStateException(
                    "Could not initialize the isolated Connect Share direct runtime",
                    e);
        }
    }

    public synchronized String peerId() {
        return invoke(peerId, String.class);
    }

    public synchronized byte[] publicKey() {
        return invoke(publicKey, byte[].class);
    }

    public synchronized DirectP2pHostInfo startHost(
            DirectP2pHostConfig config,
            DirectP2pHostHandler handler) {
        return invoke(startHost, DirectP2pHostInfo.class,
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(handler, "handler"));
    }

    public synchronized byte[] sign(byte[] payload) {
        return invoke(sign, byte[].class, Objects.requireNonNull(payload, "payload"));
    }

    public synchronized void publish(String invitation) {
        invoke(publish, Void.class, Objects.requireNonNull(invitation, "invitation"));
    }

    public synchronized DirectP2pDiscoveredShare inspect(
            String address,
            Duration timeout) {
        rejectRelayAddress(address);
        return invoke(
                inspect,
                DirectP2pDiscoveredShare.class,
                address,
                Objects.requireNonNull(timeout, "timeout"));
    }

    public synchronized void startDiscovery(DirectP2pDiscoveryListener listener) {
        invoke(
                startDiscovery,
                Void.class,
                Objects.requireNonNull(listener, "listener"));
    }

    public synchronized DirectP2pProxy openProxy(
            String address,
            String shareId,
            String capability,
            DirectP2pAuthMode authMode,
            Duration timeout) {
        rejectRelayAddress(address);
        return invoke(
                openProxy,
                DirectP2pProxy.class,
                address,
                shareId,
                capability,
                authMode,
                timeout);
    }

    @Override
    public synchronized void close() {
        if (runtime == null) {
            return;
        }
        try {
            close.invoke(runtime);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not close Connect Share direct runtime", e);
        } catch (InvocationTargetException e) {
            throw propagate("Could not close Connect Share direct runtime", e);
        } finally {
            runtime = null;
        }
    }

    private <T> T invoke(Method method, Class<T> resultType, Object... arguments) {
        if (runtime == null) {
            throw new IllegalStateException("Connect Share direct runtime is closed");
        }
        try {
            Object result = method.invoke(runtime, arguments);
            return resultType == Void.class ? null : resultType.cast(result);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not access Connect Share direct runtime", e);
        } catch (InvocationTargetException e) {
            throw propagate("Connect Share direct operation failed", e);
        }
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static RuntimeException propagate(String message, InvocationTargetException failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        return new IllegalStateException(message, cause);
    }

    public static void rejectRelayAddress(String address) {
        Objects.requireNonNull(address, "address");
        if (address.contains("/p2p-circuit") || address.contains("/circuit/")) {
            throw new IllegalArgumentException(
                    "Connect is the only supported relay for Connect Share");
        }
    }
}
