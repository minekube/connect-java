package com.minekube.connect.register;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.rpc.Code;
import com.google.rpc.Status;
import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.network.netty.LocalSession;
import com.minekube.connect.tunnel.TunnelClientTransport;
import com.minekube.connect.tunnel.TunnelConn;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.WebSocketTunnelTransport;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import com.minekube.connect.watch.SessionProposal;
import com.minekube.connect.watch.WatchClient;
import com.minekube.connect.watch.Watcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression guards for the connector side of the "released" 404 class: one session id must
 * produce exactly one local session and exactly one tunnel dial, and a session the tunnel service
 * has already abandoned must never be dialled again.
 *
 * <p>The dial is a side effect of finishing local-session setup
 * ({@code LocalSession.connect} -> {@code LocalChannelInboundHandler.channelActive} ->
 * {@code Tunneler.tunnel}), so these tests drive the real proposal intake
 * ({@code WatcherRegister.WatcherImpl.onProposal}) against a real in-process local server and a
 * recording/RFC-compliant tunnel endpoint. Counting dials is therefore counting the production
 * path, not a mock's bookkeeping.
 */
class TunnelDialAfterAbandonTest {
    private static final String SESSION_ID = "session-abandoned-1";

    private final DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup(1);
    private final AtomicInteger acceptedLocalConnections = new AtomicInteger();
    private final AtomicReference<Status> firstRejection = new AtomicReference<>();
    private final AtomicReference<Status> repeatRejection = new AtomicReference<>();

    private WatcherRegister register;
    private Channel localServer;
    private MockWebServer tunnelService;

    @AfterEach
    void tearDown() {
        if (register != null) {
            register.stop();
        }
        if (localServer != null) {
            localServer.close().syncUninterruptibly();
        }
        if (tunnelService != null) {
            try {
                tunnelService.shutdown();
            } catch (Exception ignored) {
                // The tunnel endpoint is a test fixture; a failed shutdown must not mask assertions.
            }
        }
        LocalSession.setPlatformEventLoopGroup(null);
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();
    }

    /**
     * The edge re-proposes the same session id (its unanswered-proposal retry, or a watch
     * fail-over re-publish). One session id may only ever carry one local session and one tunnel:
     * a second dial would create a second data path, which the edge's invariant forbids.
     */
    @Test
    void repeatedProposalForTheSameSessionDialsExactlyOneTunnel() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        Watcher watcher = start(transport);

        watcher.onProposal(new SessionProposal(session("ws://127.0.0.1:1/tunnel"), firstRejection::set));
        watcher.onProposal(new SessionProposal(session("ws://127.0.0.1:1/tunnel"), repeatRejection::set));

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertEquals(1, transport.dials.get(),
                        "one session id must produce exactly one tunnel dial"));

        // Give a late second dial time to show up before asserting the count again.
        Thread.sleep(500);

        assertEquals(1, transport.dials.get(),
                "one session id must produce exactly one tunnel dial");
        assertEquals(1, acceptedLocalConnections.get(),
                "one session id must produce exactly one local session");
        assertNull(repeatRejection.get(),
                "the repeat delivery is absorbed by the in-flight session; it is not a rejection");
        assertNull(firstRejection.get(),
                "the first delivery still owns the session, so it must not be rejected");
    }

    /**
     * Once the tunnel service answers "session not found" the session can never be dialled again
     * (the edge does not re-arm a released pending entry). The connector must remember that for the
     * session id, refuse to dial it again, and reject locally with a player-safe status instead of
     * an internal error.
     */
    @Test
    void sessionRefusedByTheTunnelServiceIsNeverDialledAgain() throws Exception {
        tunnelService = new MockWebServer();
        tunnelService.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("session " + SESSION_ID + " not found on tunnel service; pending metadata "
                        + "released endpoint=example request_kind=join"));
        tunnelService.start();
        String tunnelAddress = "ws://" + tunnelService.getHostName() + ":" + tunnelService.getPort() + "/";
        Watcher watcher = start(new WebSocketTunnelTransport(new OkHttpClient()));

        watcher.onProposal(new SessionProposal(session(tunnelAddress), firstRejection::set));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertNotNull(firstRejection.get(), "a refused tunnel must reject the proposal");
            assertEquals(Code.UNAVAILABLE_VALUE, firstRejection.get().getCode(),
                    "a session the edge abandoned is not an internal error; got "
                            + firstRejection.get());
        });
        await().atMost(5, SECONDS).untilAsserted(() ->
                assertEquals(1, tunnelService.getRequestCount()));

        // The edge re-proposes the same id after its 3 s answer timeout: no second dial.
        watcher.onProposal(new SessionProposal(session(tunnelAddress), repeatRejection::set));

        await().atMost(2, SECONDS).untilAsserted(() ->
                assertNotNull(repeatRejection.get(),
                        "an abandoned session must be rejected locally, never left hanging"));
        assertEquals(Code.UNAVAILABLE_VALUE, repeatRejection.get().getCode(),
                "an abandoned session must be rejected with the player-safe status; got "
                        + repeatRejection.get());

        Thread.sleep(500);

        assertEquals(1, tunnelService.getRequestCount(),
                "the connector must never dial a session the tunnel service already abandoned");
        assertEquals(1, acceptedLocalConnections.get(),
                "an abandoned session must not open a second local session");
    }

    /**
     * Defence in depth for the dial site itself: if a local session for an already abandoned id is
     * ever in flight (a delivery that raced the abandonment latch), finishing its setup must not
     * dial, and the player must be rejected instead of left waiting on a tunnel that cannot exist.
     */
    @Test
    void localSessionForAnAbandonedSessionNeverDials() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        Tunneler tunneler = new Tunneler(transport);
        abandonOutOfBand(tunneler, SESSION_ID);
        LocalAddress target = bindLocalServer();
        LocalSession.setPlatformEventLoopGroup(eventLoopGroup);

        SessionProposal proposal = new SessionProposal(
                session("ws://127.0.0.1:1/tunnel"), firstRejection::set);
        new LocalSession(
                mock(ConnectLogger.class),
                new SimpleConnectApi(mock(ConnectLogger.class)),
                tunneler,
                target,
                proposal).connect();

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertEquals(0, transport.dials.get(),
                        "channelActive must not dial a session the tunnel service abandoned"));

        assertNotNull(firstRejection.get(), "the abandoned session must be rejected locally");
        assertEquals(Code.UNAVAILABLE_VALUE, firstRejection.get().getCode(),
                "an abandoned session is not an internal error; got " + firstRejection.get());
    }

    private Watcher start(TunnelClientTransport transport) throws Exception {
        LocalAddress target = bindLocalServer();
        LocalSession.setPlatformEventLoopGroup(eventLoopGroup);

        PlatformInjector platformInjector = mock(PlatformInjector.class);
        when(platformInjector.getServerSocketAddress()).thenReturn(target);

        register = new WatcherRegister();
        WatchClient watchClient = mock(WatchClient.class);
        when(watchClient.watch(any(Watcher.class))).thenReturn(mock(WebSocket.class));
        Tunneler tunneler = new Tunneler(transport);
        inject(register, "watchClient", watchClient);
        inject(register, "tunneler", tunneler);
        // Share the tunneler's dial guard with the register, mirroring the CommonModule singleton.
        // Located reflectively so this test also compiles and runs against a tree without the fix
        // (where the guard does not exist yet) instead of failing on a missing symbol.
        injectIfPresent(register, "sessionDialGuard", dialGuardOf(tunneler));
        inject(register, "platformInjector", platformInjector);
        inject(register, "logger", mock(ConnectLogger.class));
        inject(register, "api", new SimpleConnectApi(mock(ConnectLogger.class)));
        inject(register, "libp2pEndpoint", mock(Libp2pEndpoint.class));

        register.start();

        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        org.mockito.Mockito.verify(watchClient).watch(watcher.capture());
        return watcher.getValue();
    }

    private LocalAddress bindLocalServer() throws Exception {
        LocalAddress target = new LocalAddress("connect-abandon-" + UUID.randomUUID());
        localServer = new ServerBootstrap()
                .group(eventLoopGroup)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        acceptedLocalConnections.incrementAndGet();
                    }
                })
                .bind(target)
                .sync()
                .channel();
        return target;
    }

    private static Session session(String tunnelServiceAddress) {
        return Session.newBuilder()
                .setId(SESSION_ID)
                .setTunnelServiceAddr(tunnelServiceAddress)
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(GameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000001")
                                .setName("Player")
                                .build())
                        .build())
                .build();
    }

    private static void inject(WatcherRegister register, String fieldName, Object value)
            throws Exception {
        Field field = WatcherRegister.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(register, value);
    }

    private static void injectIfPresent(WatcherRegister register, String fieldName, Object value)
            throws Exception {
        if (value == null) {
            return;
        }
        try {
            inject(register, fieldName, value);
        } catch (NoSuchFieldException ignored) {
            // Pre-fix tree: the field does not exist yet, so there is nothing to wire.
        }
    }

    private static Object dialGuardOf(Tunneler tunneler) throws Exception {
        try {
            return Tunneler.class.getMethod("dialGuard").invoke(tunneler);
        } catch (NoSuchMethodException ignored) {
            return null; // Pre-fix tree: no guard exists yet.
        }
    }

    /**
     * Marks the session abandoned the way the tunnel service's refusal does, without going through
     * the transport. Reflective so the test still runs (and fails on its assertions, not a missing
     * symbol) against a tree without the abandonment latch.
     */
    private static void abandonOutOfBand(Tunneler tunneler, String sessionId) throws Exception {
        Object guard = dialGuardOf(tunneler);
        if (guard == null) {
            return;
        }
        guard.getClass().getMethod("abandon", String.class).invoke(guard, sessionId);
    }

    /** Records every tunnel dial and never opens a tunnel, like a tunnel service that refuses. */
    private static final class RecordingTransport implements TunnelClientTransport {
        private final AtomicInteger dials = new AtomicInteger();

        @Override
        public TunnelTransport.Type type() {
            return TunnelTransport.Type.TYPE_WEBSOCKET;
        }

        @Override
        public TunnelConn tunnel(String address, String sessionId, TunnelConn.Handler handler) {
            dials.incrementAndGet();
            return new TunnelConn() {
                @Override
                public void write(byte[] data) {
                }

                @Override
                public void close(Throwable t) {
                }
            };
        }
    }
}
