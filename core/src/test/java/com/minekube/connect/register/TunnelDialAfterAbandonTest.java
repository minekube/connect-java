package com.minekube.connect.register;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.rpc.Code;
import com.google.rpc.Status;
import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.network.netty.LocalSession;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.tunnel.SessionDialGuard;
import com.minekube.connect.tunnel.TunnelClientTransport;
import com.minekube.connect.tunnel.TunnelConn;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.WebSocketTunnelTransport;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import com.minekube.connect.watch.SessionProposal;
import com.minekube.connect.watch.WatchClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchRequest;
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    /** Rejections the connector sent over the watch socket, in order. */
    private final List<Status> wireRejections = new CopyOnWriteArrayList<>();

    private WatcherRegister register;
    private LocalAddress localAddress;
    private WebSocketListener watchListener;
    private final WebSocket watchSocket = mock(WebSocket.class);
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
        start(transport);

        deliver(SESSION_ID, "ws://127.0.0.1:1/tunnel");
        deliver(SESSION_ID, "ws://127.0.0.1:1/tunnel");
        // A different session sent after the repeat: once it is dialled, everything queued before
        // it on the single local event loop has run, so a second dial of SESSION_ID would be
        // visible by now.
        deliver("session-sentinel", "ws://127.0.0.1:1/tunnel");

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertTrue(transport.dialled.contains("session-sentinel")));

        assertEquals(1, transport.dialled.stream().filter(SESSION_ID::equals).count(),
                "one session id must produce exactly one tunnel dial");
        assertEquals(2, acceptedLocalConnections.get(),
                "one local session per session id: the session and the sentinel");
        assertTrue(wireRejections.isEmpty(),
                "the repeat delivery is absorbed by the in-flight session; it is not a rejection");
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
                .setBody("session " + SESSION_ID + " not found on tunnel service; pendingState=released "
                        + "endpoint=example request_kind=join"));
        tunnelService.start();
        String tunnelAddress = "ws://" + tunnelService.getHostName() + ":" + tunnelService.getPort() + "/";
        start(new WebSocketTunnelTransport(new OkHttpClient()));

        deliver(SESSION_ID, tunnelAddress);

        await().atMost(5, SECONDS).untilAsserted(() -> {
            assertEquals(1, wireRejections.size(), "a refused tunnel must reject the proposal");
            assertEquals(Code.UNAVAILABLE_VALUE, wireRejections.get(0).getCode(),
                    "a session the edge abandoned is not an internal error; got "
                            + wireRejections.get(0));
        });
        assertEquals(1, tunnelService.getRequestCount());

        // The edge re-proposes the same id after its 3 s answer timeout: no second dial.
        deliver(SESSION_ID, tunnelAddress);

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertEquals(2, wireRejections.size(),
                        "an abandoned session must be rejected locally, never left hanging"));
        assertEquals(Code.UNAVAILABLE_VALUE, wireRejections.get(1).getCode(),
                "an abandoned session must be rejected with the player-safe status; got "
                        + wireRejections.get(1));
        assertEquals(1, tunnelService.getRequestCount(),
                "the connector must never dial a session the tunnel service already abandoned");
    }

    /**
     * A delivery that fails locally before it dials (here: the local server is gone) is rejected,
     * and that frees the claim: the edge's redelivery must be served, not dropped without a reply.
     */
    @Test
    void redeliveryAfterATransientLocalFailureIsServed() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        start(transport);
        localServer.close().syncUninterruptibly();
        localServer = null;

        deliver(SESSION_ID, "ws://127.0.0.1:1/tunnel");
        await().atMost(5, SECONDS).untilAsserted(() ->
                assertEquals(1, wireRejections.size(), "the failed delivery must be rejected"));

        bindLocalServer(localAddress);
        deliver(SESSION_ID, "ws://127.0.0.1:1/tunnel");

        await().atMost(5, SECONDS).untilAsserted(() ->
                assertEquals(1, transport.dialled.size(),
                        "the redelivery after the failure must be dialled"));
    }

    /**
     * Defence in depth for the dial site itself: if a local session for an already abandoned id is
     * ever in flight (a delivery that raced the abandonment latch), finishing its setup must not
     * dial, and the player must be rejected instead of left waiting on a tunnel that cannot exist.
     */
    @Test
    void localSessionForAnAbandonedSessionNeverDials() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        SessionDialGuard guard = new SessionDialGuard();
        guard.abandon(SESSION_ID);
        Tunneler tunneler = new Tunneler(transport, guard);
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
                assertTrue(transport.dialled.isEmpty(),
                        "channelActive must not dial a session the tunnel service abandoned"));

        assertNotNull(firstRejection.get(), "the abandoned session must be rejected locally");
        assertEquals(Code.UNAVAILABLE_VALUE, firstRejection.get().getCode(),
                "an abandoned session is not an internal error; got " + firstRejection.get());
    }

    private void start(TunnelClientTransport transport) throws Exception {
        LocalAddress target = bindLocalServer();
        localAddress = target;
        LocalSession.setPlatformEventLoopGroup(eventLoopGroup);

        PlatformInjector platformInjector = mock(PlatformInjector.class);
        when(platformInjector.getServerSocketAddress()).thenReturn(target);

        // Real WatchClient over a captured socket listener: deliveries take the production path
        // (claim, proposal, WatcherRegister.onProposal) and rejections go out as wire messages.
        SessionDialGuard guard = new SessionDialGuard();
        OkHttpClient httpClient = mock(OkHttpClient.class);
        when(httpClient.newWebSocket(any(Request.class), any(WebSocketListener.class)))
                .thenAnswer(invocation -> {
                    watchListener = invocation.getArgument(1);
                    return watchSocket;
                });
        when(watchSocket.send(any(ByteString.class))).thenAnswer(invocation -> {
            ByteString sent = invocation.getArgument(0);
            wireRejections.add(WatchRequest.parseFrom(sent.toByteArray())
                    .getSessionRejection().getReason());
            return true;
        });

        register = new WatcherRegister();
        inject(register, "watchClient", new WatchClient(httpClient, new ConnectConfig(), guard));
        inject(register, "tunneler", new Tunneler(transport, guard));
        inject(register, "platformInjector", platformInjector);
        inject(register, "logger", mock(ConnectLogger.class));
        inject(register, "api", new SimpleConnectApi(mock(ConnectLogger.class)));
        inject(register, "libp2pEndpoint", mock(Libp2pEndpoint.class));

        register.start();
        assertNotNull(watchListener, "the register must open the watch");
    }

    private void deliver(String sessionId, String tunnelServiceAddress) {
        watchListener.onMessage(watchSocket, ByteString.of(WatchResponse.newBuilder()
                .setSession(session(sessionId, tunnelServiceAddress)).build().toByteArray()));
    }

    private LocalAddress bindLocalServer() throws Exception {
        LocalAddress target = new LocalAddress("connect-abandon-" + UUID.randomUUID());
        bindLocalServer(target);
        return target;
    }

    private void bindLocalServer(LocalAddress target) throws Exception {
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
    }

    private static Session session(String tunnelServiceAddress) {
        return session(SESSION_ID, tunnelServiceAddress);
    }

    private static Session session(String sessionId, String tunnelServiceAddress) {
        return Session.newBuilder()
                .setId(sessionId)
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

    /** Records every tunnel dial and never opens a tunnel, like a tunnel service that refuses. */
    private static final class RecordingTransport implements TunnelClientTransport {
        private final List<String> dialled = new CopyOnWriteArrayList<>();

        @Override
        public TunnelTransport.Type type() {
            return TunnelTransport.Type.TYPE_WEBSOCKET;
        }

        @Override
        public TunnelConn tunnel(String address, String sessionId, TunnelConn.Handler handler) {
            dialled.add(sessionId);
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
