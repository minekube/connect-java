package com.minekube.connect.tunnel;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport.Type;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

class TunnelerTest {

    @Test
    void delegatesToConfiguredTransport() {
        RecordingTransport transport = new RecordingTransport(Type.TYPE_WEBSOCKET);
        TunnelConn expected = new TunnelConn() {
            @Override
            public void write(byte[] data) {
            }

            @Override
            public void close(Throwable t) {
            }
        };
        transport.next = expected;
        CapturingHandler handler = new CapturingHandler();
        Tunneler tunneler = new Tunneler(transport);

        TunnelConn actual = tunneler.tunnel("ws://connect.example/tunnel", "session-123", handler);
        tunneler.close();

        assertSame(expected, actual);
        assertSame(handler, transport.handler);
        assertArrayEquals(new String[] {"ws://connect.example/tunnel", "session-123"}, transport.args);
        if (!transport.closed) {
            fail("transport was not closed");
        }
    }

    @Test
    void prefersAdvertisedLibp2pTransportOverWebsocketFallback() {
        RecordingTransport websocket = new RecordingTransport(Type.TYPE_WEBSOCKET);
        RecordingTransport libp2p = new RecordingTransport(Type.TYPE_LIBP2P);
        TunnelConn expected = new TunnelConn() {
            @Override
            public void write(byte[] data) {
            }

            @Override
            public void close(Throwable t) {
            }
        };
        websocket.next = new TunnelConn() {
            @Override
            public void write(byte[] data) {
            }

            @Override
            public void close(Throwable t) {
            }
        };
        libp2p.next = expected;
        CapturingHandler handler = new CapturingHandler();
        Tunneler tunneler = new Tunneler(new HashSet<>(Arrays.asList(websocket, libp2p)));

        TunnelConn actual = tunneler.tunnel(session(
                "session-456",
                "ws://connect.example/tunnel",
                transport(Type.TYPE_LIBP2P, "/ip4/127.0.0.1/tcp/1/p2p/test")
        ), handler);

        assertSame(expected, actual);
        assertSame(handler, libp2p.handler);
        assertArrayEquals(new String[] {"/ip4/127.0.0.1/tcp/1/p2p/test", "session-456"},
                libp2p.args);
        if (websocket.args != null) {
            fail("websocket fallback should not be used when libp2p succeeds");
        }
    }

    @Test
    void fallsBackToTunnelServiceAddrWhenAdvertisedTransportIsUnavailable() {
        RecordingTransport websocket = new RecordingTransport(Type.TYPE_WEBSOCKET);
        TunnelConn expected = new TunnelConn() {
            @Override
            public void write(byte[] data) {
            }

            @Override
            public void close(Throwable t) {
            }
        };
        websocket.next = expected;
        Tunneler tunneler = new Tunneler(websocket);

        TunnelConn actual = tunneler.tunnel(session(
                "session-789",
                "ws://connect.example/fallback",
                transport(Type.TYPE_LIBP2P, "/ip4/127.0.0.1/tcp/1/p2p/test")
        ), new CapturingHandler());

        assertSame(expected, actual);
        assertArrayEquals(new String[] {"ws://connect.example/fallback", "session-789"},
                websocket.args);
    }

    @Test
    void preparesSelectedTransports() {
        RecordingTransport websocket = new RecordingTransport(Type.TYPE_WEBSOCKET);
        RecordingTransport libp2p = new RecordingTransport(Type.TYPE_LIBP2P);
        Tunneler tunneler = new Tunneler(new HashSet<>(Arrays.asList(websocket, libp2p)));

        tunneler.prepare(session(
                "session-prepare",
                "ws://connect.example/fallback",
                transport(Type.TYPE_LIBP2P, "/ip4/127.0.0.1/tcp/1/p2p/test")
        ));

        assertArrayEquals(new String[] {"/ip4/127.0.0.1/tcp/1/p2p/test"},
                libp2p.prepared);
    }

    @Test
    void prepareDoesNotPreventWebsocketFallbackWhenLibp2pWarmupFails() {
        RecordingTransport websocket = new RecordingTransport(Type.TYPE_WEBSOCKET);
        RecordingTransport libp2p = new RecordingTransport(Type.TYPE_LIBP2P);
        libp2p.prepareFailure = new IllegalStateException("stale libp2p peer");
        libp2p.tunnelFailure = new IllegalStateException("stale libp2p peer");
        TunnelConn expected = new TunnelConn() {
            @Override
            public void write(byte[] data) {
            }

            @Override
            public void close(Throwable t) {
            }
        };
        websocket.next = expected;
        Tunneler tunneler = new Tunneler(new HashSet<>(Arrays.asList(websocket, libp2p)));
        Session session = session(
                "session-prepare-fallback",
                "ws://connect.example/fallback",
                transport(Type.TYPE_LIBP2P, "/ip4/127.0.0.1/tcp/1/p2p/test")
        );

        assertDoesNotThrow(() -> tunneler.prepare(session));
        TunnelConn actual = tunneler.tunnel(session, new CapturingHandler());

        assertSame(expected, actual);
        assertArrayEquals(new String[] {"ws://connect.example/fallback", "session-prepare-fallback"},
                websocket.args);
    }

    @Test
    void receivesBinaryFrameFromTunnelService() throws Exception {
        byte[] sent = new byte[] {1, 2, 3, 4, 5};

        byte[] received = receiveServerMessage(sent);

        assertArrayEquals(sent, received);
    }

    @Test
    void receivesBinaryFrameWhenByteStringDataFieldIsUnavailable() throws Exception {
        byte[] sent = new byte[] {9, 8, 7, 6};
        Field dataField = WebSocketTunnelTransport.class.getDeclaredField("DATA");
        dataField.setAccessible(true);
        Field originalData = (Field) dataField.get(null);

        try {
            setStaticField(dataField, null);

            byte[] received = receiveServerMessage(sent);

            assertArrayEquals(sent, received);
        } finally {
            setStaticField(dataField, originalData);
        }
    }

    @Test
    void sendsFlyForceInstanceHeaderWhenTunnelAddressTargetsFlyMachine() throws Exception {
        OkHttpClient client = new OkHttpClient();
        TunnelConn conn = null;

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            }));
            server.start();

            String tunnelUrl = webSocketUrl(server) + "?fi=machine-123&cid=";
            conn = new Tunneler(new WebSocketTunnelTransport(client))
                    .tunnel(tunnelUrl, "session-123", new CapturingHandler());
            RecordedRequest request = server.takeRequest(5, SECONDS);

            assertNotNull(request);
            assertEquals("session-123", request.getHeader("Connect-Session"));
            assertEquals("machine-123", request.getHeader("Fly-Force-Instance-Id"));
        } finally {
            if (conn != null) {
                conn.close();
            }
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    @Test
    void omitsFlyForceInstanceHeaderWhenTunnelAddressHasNoFlyTarget() throws Exception {
        OkHttpClient client = new OkHttpClient();
        TunnelConn conn = null;

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            }));
            server.start();

            conn = new Tunneler(new WebSocketTunnelTransport(client))
                    .tunnel(webSocketUrl(server), "session-456", new CapturingHandler());
            RecordedRequest request = server.takeRequest(5, SECONDS);

            assertNotNull(request);
            assertEquals("session-456", request.getHeader("Connect-Session"));
            assertNull(request.getHeader("Fly-Force-Instance-Id"));
        } finally {
            if (conn != null) {
                conn.close();
            }
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    private static byte[] receiveServerMessage(byte[] sent) throws Exception {
        OkHttpClient client = new OkHttpClient();
        CapturingHandler handler = new CapturingHandler();
        TunnelConn conn = null;

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override
                public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                    webSocket.send(ByteString.of(sent));
                }
            }));
            server.start();

            conn = new Tunneler(new WebSocketTunnelTransport(client))
                    .tunnel(webSocketUrl(server), "test-session", handler);

            return handler.awaitReceived();
        } finally {
            if (conn != null) {
                conn.close();
            }
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    private static String webSocketUrl(MockWebServer server) {
        return server.url("/tunnel").toString().replaceFirst("^http:", "ws:");
    }

    private static void setStaticField(Field field, Object value) throws Exception {
        try {
            field.set(null, value);
            return;
        } catch (IllegalAccessException ignored) {
        }

        Object unsafe = unsafe();
        Method staticFieldBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);
        Method staticFieldOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
        Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
        Object base = staticFieldBase.invoke(unsafe, field);
        long offset = (Long) staticFieldOffset.invoke(unsafe, field);
        putObject.invoke(unsafe, base, offset, value);
    }

    private static Object unsafe() throws Exception {
        Field unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        return unsafe.get(null);
    }

    private static final class CapturingHandler implements TunnelConn.Handler {
        private final AtomicReference<byte[]> received = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onReceive(byte[] data) {
            received.set(data);
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        private byte[] awaitReceived() {
            await().atMost(5, SECONDS).untilAsserted(() -> {
                Throwable thrown = error.get();
                if (thrown != null) {
                    fail("Tunnel handler received an error", thrown);
                }
                assertNotNull(received.get());
            });
            return received.get();
        }
    }

    private static Session session(String id, String tunnelServiceAddr, TunnelTransport... transports) {
        Session.Builder builder = Session.newBuilder()
                .setId(id)
                .setTunnelServiceAddr(tunnelServiceAddr);
        for (TunnelTransport transport : transports) {
            builder.addTunnelTransports(transport);
        }
        return builder.build();
    }

    private static TunnelTransport transport(Type type, String address) {
        return TunnelTransport.newBuilder()
                .setType(type)
                .setAddress(address)
                .build();
    }

    private static final class RecordingTransport implements TunnelClientTransport {
        private final Type type;
        private TunnelConn next;
        private String[] args;
        private String[] prepared;
        private TunnelConn.Handler handler;
        private RuntimeException prepareFailure;
        private RuntimeException tunnelFailure;
        private boolean closed;

        private RecordingTransport(Type type) {
            this.type = type;
        }

        @Override
        public Type type() {
            return type;
        }

        @Override
        public void prepare(String address) {
            if (prepareFailure != null) {
                throw prepareFailure;
            }
            this.prepared = new String[] {address};
        }

        @Override
        public TunnelConn tunnel(String address, String sessionId, TunnelConn.Handler handler) {
            if (tunnelFailure != null) {
                throw tunnelFailure;
            }
            this.args = new String[] {address, sessionId};
            this.handler = handler;
            return next;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
