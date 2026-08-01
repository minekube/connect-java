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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectP2pNodeTest {
    @TempDir
    java.nio.file.Path tempDir;

    private DirectP2pNode host;
    private DirectP2pNode guest;

    @AfterEach
    void closeNodes() {
        if (guest != null) {
            guest.close();
        }
        if (host != null) {
            host.close();
        }
        Libp2pRuntime.close();
    }

    @Test
    void twoLoopbackNodesExchangeMinecraftShapedBytes() throws Exception {
        byte[] minecraftHandshake = new byte[] {
                0x10, 0x00, (byte) 0xff, 0x01, 0x7f, 0x45, 0x00
        };
        AtomicReference<DirectP2pSession> session = new AtomicReference<>();
        try (ServerSocket target = new ServerSocket()) {
            target.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            CompletableFuture<Void> echo = CompletableFuture.runAsync(() -> {
                try (Socket accepted = target.accept()) {
                    byte[] received = new DataInputStream(accepted.getInputStream())
                            .readNBytes(minecraftHandshake.length);
                    new DataOutputStream(accepted.getOutputStream()).write(received);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            host = new DirectP2pNode();
            DirectP2pHostInfo hostInfo = host.startHost(
                    new DirectP2pHostConfig(
                            "share-123",
                            "capability-123456789",
                            "Robin's World",
                            false),
                    directSession -> {
                        session.set(directSession);
                        Socket socket = new Socket();
                        socket.connect(target.getLocalSocketAddress());
                        return socket;
                    });
            guest = new DirectP2pNode();
            DirectP2pProxy proxy = guest.openProxy(
                    hostInfo.lanAddresses().get(0),
                    "share-123",
                    "capability-123456789",
                    DirectP2pAuthMode.OFFLINE,
                    Duration.ofSeconds(3));

            try (Socket minecraftClient = new Socket()) {
                minecraftClient.connect(proxy.localAddress());
                minecraftClient.getOutputStream().write(minecraftHandshake);
                assertArrayEquals(
                        minecraftHandshake,
                        minecraftClient.getInputStream().readNBytes(minecraftHandshake.length));
            } finally {
                proxy.close();
            }

            echo.get(3, TimeUnit.SECONDS);
            assertEquals(DirectP2pAuthMode.OFFLINE, session.get().authMode());
            assertEquals(DirectP2pRoute.LAN, session.get().route());
            assertFalse(session.get().peerId().isBlank());
            assertFalse(session.get().connectionId().isBlank());
        }
    }

    @Test
    void everyHostUsesAnEphemeralPeerIdentityAndSignsWithIt() throws Exception {
        host = new DirectP2pNode();
        DirectP2pHostInfo first = host.startHost(
                new DirectP2pHostConfig("one", "capability-one", "One", false),
                ignored -> new Socket());
        byte[] message = "signed invitation body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = host.sign(message);

        try (DirectP2pNode other = new DirectP2pNode()) {
            DirectP2pHostInfo second = other.startHost(
                    new DirectP2pHostConfig("two", "capability-two", "Two", false),
                    ignored -> new Socket());

            assertNotEquals(first.peerId(), second.peerId());
        }

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(first.publicKey())));
        verifier.update(message);
        assertTrue(verifier.verify(signature));
    }

    @Test
    void persistentIdentitySurvivesNodeRestarts() {
        java.nio.file.Path identityFile = tempDir.resolve("share-peer.key");
        String firstPeerId;

        host = new DirectP2pNode(identityFile);
        firstPeerId = host.startHost(
                new DirectP2pHostConfig(
                        "first-share",
                        "first-capability",
                        "First World",
                        false),
                ignored -> new Socket()).peerId();
        host.close();
        host = null;
        Libp2pRuntime.close();

        host = new DirectP2pNode(identityFile);
        String restartedPeerId = host.startHost(
                new DirectP2pHostConfig(
                        "second-share",
                        "second-capability",
                        "Second World",
                        false),
                ignored -> new Socket()).peerId();

        assertEquals(firstPeerId, restartedPeerId);
        assertTrue(java.nio.file.Files.isRegularFile(identityFile));
    }

    @Test
    void persistentPeerIdentityIsAvailableWithoutOpeningAWorld() {
        java.nio.file.Path identityFile = tempDir.resolve("friend-peer.key");

        host = new DirectP2pNode(identityFile);
        String firstPeerId = host.peerId();
        host.close();
        host = null;
        Libp2pRuntime.close();

        host = new DirectP2pNode(identityFile);

        assertEquals(firstPeerId, host.peerId());
        assertFalse(firstPeerId.isBlank());
    }

    @Test
    void persistentIdentityCanSignAFriendCardWithoutOpeningAWorld() throws Exception {
        host = new DirectP2pNode(tempDir.resolve("friend-card-peer.key"));
        byte[] message = "friend card".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] signature = host.sign(message);

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(host.publicKey())));
        verifier.update(message);
        assertTrue(verifier.verify(signature));
    }

    @Test
    void publishedHostMetadataCanBeInspectedWithoutAdvertisingItsCapability() {
        host = new DirectP2pNode();
        DirectP2pHostInfo hostInfo = host.startHost(
                new DirectP2pHostConfig(
                        "share-inspect",
                        "capability-inspect",
                        "Robin's World",
                        false),
                ignored -> new Socket());
        host.publish(
                "minekube://share/signed-secret-payload",
                "minekube://share/signed-lan-payload");

        guest = new DirectP2pNode();
        DirectP2pDiscoveredShare discovered = guest.inspect(
                hostInfo.lanAddresses().get(0),
                Duration.ofSeconds(3));

        assertEquals("Robin's World", discovered.displayName());
        assertEquals(hostInfo.peerId(), discovered.peerId());
        assertEquals(
                "minekube://share/signed-lan-payload",
                discovered.invitation());
        assertFalse(discovered.toString().contains("signed-lan-payload"));
        assertFalse(discovered.toString().contains(hostInfo.lanAddresses().get(0)));
    }

    @Test
    void discoveryNodeCanBecomeThePublishedHostWithoutChangingItsPeer() {
        host = new DirectP2pNode();
        String peerId = host.peerId();
        host.startDiscovery(ignored -> { });

        DirectP2pHostInfo hostInfo = host.startHost(
                new DirectP2pHostConfig(
                        "shared-runtime",
                        "shared-capability-123456789",
                        "Shared runtime",
                        false),
                ignored -> new Socket());
        host.publish("minekube://share/shared-runtime");

        guest = new DirectP2pNode();
        DirectP2pDiscoveredShare discovered = guest.inspect(
                hostInfo.lanAddresses().get(0),
                Duration.ofSeconds(3));

        assertEquals(peerId, hostInfo.peerId());
        assertEquals(peerId, discovered.peerId());
        assertEquals(
                "minekube://share/shared-runtime",
                discovered.invitation());
    }

    @Test
    void publishedHostCanRefreshItsWorldWithoutChangingItsPeer() {
        host = new DirectP2pNode();
        DirectP2pHostInfo first = host.startHost(
                new DirectP2pHostConfig(
                        "stable-share",
                        "stable-capability-123456789",
                        "First world",
                        false),
                ignored -> new Socket());
        host.publish("minekube://share/first-world");

        DirectP2pHostInfo second = host.startHost(
                new DirectP2pHostConfig(
                        "stable-share",
                        "stable-capability-123456789",
                        "Second world",
                        true),
                ignored -> new Socket());
        host.publish("minekube://share/second-world");

        guest = new DirectP2pNode();
        DirectP2pDiscoveredShare discovered = guest.inspect(
                second.lanAddresses().get(0),
                Duration.ofSeconds(3));

        assertEquals(first.peerId(), second.peerId());
        assertEquals("Second world", discovered.displayName());
        assertEquals("minekube://share/second-world", discovered.invitation());
    }

    @Test
    void mdnsTxtLengthPrefixSupportsModernEd25519PeerIds() {
        String peerId =
                "12D3KooWEHeJnnq1Rfwt679bTyTxkEdtyTC8peAJWsWCxtAJ4s9y";
        byte[] encodedPeerId = peerId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] txtRecord = new byte[encodedPeerId.length + 1];
        txtRecord[0] = (byte) encodedPeerId.length;
        System.arraycopy(encodedPeerId, 0, txtRecord, 1, encodedPeerId.length);

        assertEquals(
                peerId,
                DirectP2pNodeRuntime.decodeMdnsPeerId(txtRecord));
    }

    @Test
    void mdnsHostNameComesFromPeerIdentityWithoutDnsResolution() {
        String peerId =
                "12D3KooWEHeJnnq1Rfwt679bTyTxkEdtyTC8peAJWsWCxtAJ4s9y";

        String hostName = DirectP2pNodeRuntime.mdnsHostName(peerId);

        assertEquals(
                "connect-share-12D3KooWEHeJnnq1Rfwt679bTyTxkEdt",
                hostName);
        assertTrue(hostName.length() <= 63);
    }

    @Test
    void directNodeNeverAdvertisesOrAcceptsCircuitRelayAddresses() {
        host = new DirectP2pNode();
        DirectP2pHostInfo info = host.startHost(
                new DirectP2pHostConfig("share", "capability", "World", true),
                ignored -> new Socket());

        assertTrue(info.lanAddresses().stream().noneMatch(it -> it.contains("p2p-circuit")));
        assertTrue(info.internetAddresses().stream().noneMatch(it -> it.contains("p2p-circuit")));

        guest = new DirectP2pNode();
        assertThrows(IllegalArgumentException.class, () -> guest.openProxy(
                "/ip4/203.0.113.2/tcp/4001/p2p/QmRelay/p2p-circuit/p2p/QmHost",
                "share",
                "capability",
                DirectP2pAuthMode.ONLINE,
                Duration.ofSeconds(3)));
    }

    @Test
    void parentBoundaryUsesOnlyJdkTypes() {
        List<Class<?>> boundary = List.of(
                DirectP2pNode.class,
                DirectP2pHostConfig.class,
                DirectP2pHostInfo.class,
                DirectP2pHostHandler.class,
                DirectP2pSession.class,
                DirectP2pDiscoveredShare.class,
                DirectP2pDiscoveryListener.class,
                DirectP2pProxy.class,
                DirectP2pRoute.class,
                DirectP2pAuthMode.class);

        for (Class<?> type : boundary) {
            java.util.stream.Stream.concat(
                            java.util.Arrays.stream(type.getDeclaredMethods())
                                    .flatMap(method -> java.util.stream.Stream.concat(
                                            java.util.stream.Stream.of(method.getReturnType()),
                                            java.util.Arrays.stream(method.getParameterTypes()))),
                            java.util.Arrays.stream(type.getDeclaredFields())
                                    .map(java.lang.reflect.Field::getType))
                    .map(Class::getName)
                    .forEach(name -> {
                        assertFalse(name.startsWith("io.libp2p."), name);
                        assertFalse(name.startsWith("io.netty."), name);
                        assertFalse(name.startsWith("kotlin."), name);
                        assertFalse(name.startsWith("kotlinx."), name);
                    });
        }
    }
}
