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

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.minekube.connect.tunnel.TunnelClientTransport;
import java.util.List;
import org.junit.jupiter.api.Test;

class Libp2pRuntimeLoaderTest {
    @Test
    void loadsRuntimeClassesChildFirstAndSharedApiClassesFromParent() throws ClassNotFoundException {
        ClassLoader parent = Libp2pEndpoint.class.getClassLoader();
        ClassLoader runtimeLoader = Libp2pRuntimeLoader.classLoader();

        for (String className : List.of(
                "com.minekube.connect.tunnel.p2p.EndpointPeerIdentity",
                "com.minekube.connect.tunnel.p2p.Libp2pEndpointConfig",
                "com.minekube.connect.tunnel.p2p.Libp2pEndpointRuntime",
                "com.minekube.connect.tunnel.p2p.Libp2pSessionMapper",
                "com.minekube.connect.tunnel.p2p.Libp2pSessionResponder",
                "com.minekube.connect.tunnel.p2p.Libp2pStatusReporter",
                "com.minekube.connect.tunnel.p2p.P2PFrameCodec",
                "com.minekube.connect.tunnel.p2p.P2PFrameDecoder",
                "com.minekube.connect.tunnel.p2p.PeerRecordSigningPayload",
                "com.minekube.connect.tunnel.p2p.PeerRegistrationClient",
                "com.minekube.connect.tunnel.p2p.PeerRegistrationHandshake",
                "com.minekube.connect.tunnel.p2p.SameStreamTunnelTransport",
                "com.minekube.connect.tunnel.p2p.impl.Libp2pTunnelTransportRuntime",
                "io.libp2p.core.Host",
                "io.netty.channel.Channel")) {
            Class<?> runtimeClass = Class.forName(className, false, runtimeLoader);
            assertSame(runtimeLoader, runtimeClass.getClassLoader(), className);
        }

        Class<?> sharedTransportApi = Class.forName(
                "com.minekube.connect.tunnel.TunnelClientTransport",
                false,
                runtimeLoader);

        assertSame(parent, Class.forName("com.minekube.connect.tunnel.p2p.Libp2pEndpoint", false, runtimeLoader).getClassLoader());
        assertSame(parent, Class.forName("com.minekube.connect.tunnel.p2p.Libp2pTunnelTransport", false, runtimeLoader).getClassLoader());
        assertNotSame(parent, runtimeLoader);
        assertSame(TunnelClientTransport.class, sharedTransportApi);
    }
}
