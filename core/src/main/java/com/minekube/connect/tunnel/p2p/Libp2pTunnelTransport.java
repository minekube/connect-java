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
import com.minekube.connect.tunnel.TunnelClientTransport;
import com.minekube.connect.tunnel.TunnelConn;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport.Type;

public final class Libp2pTunnelTransport implements TunnelClientTransport {
    private final TunnelClientTransport delegate;
    private final RuntimeException unavailable;

    @Inject
    public Libp2pTunnelTransport() {
        TunnelClientTransport created = null;
        RuntimeException failure = null;
        try {
            Class<?> runtimeClass = Class.forName(
                    "com.minekube.connect.tunnel.p2p.impl.Libp2pTunnelTransportRuntime",
                    true,
                    Libp2pRuntimeLoader.classLoader());
            created = (TunnelClientTransport) runtimeClass.getDeclaredConstructor().newInstance();
        } catch (Exception | LinkageError e) {
            failure = new IllegalStateException("Connect libp2p transport runtime unavailable", e);
        }
        this.delegate = created;
        this.unavailable = failure;
    }

    Libp2pTunnelTransport(TunnelClientTransport delegate) {
        this.delegate = delegate;
        this.unavailable = null;
    }

    @Override
    public Type type() {
        return Type.TYPE_LIBP2P;
    }

    @Override
    public void prepare(String address) {
        if (delegate != null) {
            delegate.prepare(address);
        }
    }

    @Override
    public TunnelConn tunnel(String address, String sessionId, TunnelConn.Handler handler) {
        if (delegate == null) {
            throw unavailable;
        }
        return delegate.tunnel(address, sessionId, handler);
    }

    @Override
    public void close() {
        if (delegate != null) {
            delegate.close();
        }
    }
}
