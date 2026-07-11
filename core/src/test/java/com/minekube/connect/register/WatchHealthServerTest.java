package com.minekube.connect.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.tunnel.Tunneler;
import com.minekube.connect.tunnel.p2p.Libp2pEndpoint;
import com.minekube.connect.watch.WatchBootstrap;
import com.minekube.connect.watch.WatchClient;
import com.minekube.connect.watch.Watcher;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.ArgumentCaptor;
import okhttp3.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WatchHealthServerTest {
    private WatchHealthServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void healthzReturnsOkWhenWatcherIsHealthy() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(true);
        server = new WatchHealthServer(loopbackAddress(), healthy::get, mock(ConnectLogger.class));

        assertEquals(200, responseCode(server.url("/healthz")));
    }

    @Test
    void healthzReturnsUnavailableWhenWatcherIsUnhealthy() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(false);
        server = new WatchHealthServer(loopbackAddress(), healthy::get, mock(ConnectLogger.class));

        assertEquals(503, responseCode(server.url("/healthz")));
    }

    @Test
    void healthzReflectsWatcherHealthChanges() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(false);
        server = new WatchHealthServer(loopbackAddress(), healthy::get, mock(ConnectLogger.class));

        assertEquals(503, responseCode(server.url("/healthz")));

        healthy.set(true);

        assertEquals(200, responseCode(server.url("/healthz")));
    }

    @Test
    void unknownPathReturnsNotFound() throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(true);
        server = new WatchHealthServer(loopbackAddress(), healthy::get, mock(ConnectLogger.class));

        assertEquals(404, responseCode(server.url("/missing")));
    }

    @Test
    void publicHealthEndpointTracksAcceptedWatcherLifecycle() throws Exception {
        WatcherFixture fixture = newWatcherFixture();
        WatcherRegister register = fixture.register;
        server = new WatchHealthServer(loopbackAddress(), register::isHealthy, mock(ConnectLogger.class));

        assertResponse("before-start", response(server.url("/healthz")), 503, "unhealthy\n");
        register.start();
        assertResponse("connecting", response(server.url("/healthz")), 503, "unhealthy\n");

        ArgumentCaptor<Watcher> watchers = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watchers.capture());
        watchers.getValue().onOpen(emptyBootstrap());
        assertResponse("watch-open", response(server.url("/healthz")), 200, "ok\n");

        watchers.getValue().onCompleted();
        assertResponse("watch-completed", response(server.url("/healthz")), 503, "unhealthy\n");

        register.stop();
        register.start();
        verify(fixture.watchClient, times(2)).watch(watchers.capture());
        Watcher restartedWatcher = watchers.getAllValues().get(2);
        restartedWatcher.onOpen(emptyBootstrap());
        assertResponse("restarted-open", response(server.url("/healthz")), 200, "ok\n");

        register.stop();
        assertResponse("stopped", response(server.url("/healthz")), 503, "unhealthy\n");
    }

    @Test
    void publicHealthEndpointStaysHealthyWhenWatchlessModeIgnoresTerminalEvent() throws Exception {
        WatcherFixture fixture = newWatcherFixture();
        CapturedCallbacks callbacks = new CapturedCallbacks();
        doAnswer(invocation -> {
            callbacks.ready = invocation.getArgument(3);
            return null;
        }).when(fixture.libp2pEndpoint).start(
                any(), any(), anyBoolean(), any(Runnable.class), any(Runnable.class));
        WatcherRegister register = fixture.register;
        server = new WatchHealthServer(loopbackAddress(), register::isHealthy, mock(ConnectLogger.class));
        register.start();
        ArgumentCaptor<Watcher> watcher = ArgumentCaptor.forClass(Watcher.class);
        verify(fixture.watchClient).watch(watcher.capture());
        watcher.getValue().onOpen(WatchBootstrap.fromLists(
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("/dns4/connect.example/tcp/4001/p2p/edge"),
                List.of("session", "status", "watchless")));
        assertResponse("watchless-negotiated", response(server.url("/healthz")), 200, "ok\n");

        callbacks.ready.run();
        watcher.getValue().onCompleted();

        assertResponse("watchless-terminal-ignored", response(server.url("/healthz")), 200, "ok\n");
        register.stop();
    }

    private static String loopbackAddress() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return "127.0.0.1:" + socket.getLocalPort();
        }
    }

    private static int responseCode(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static Response response(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, body);
        } finally {
            connection.disconnect();
        }
    }

    private static void assertResponse(String state, Response response, int status, String body) {
        System.out.printf("%-28s GET /healthz -> %d %s", state, response.status, response.body);
        assertEquals(status, response.status);
        assertEquals(body, response.body);
    }

    private static WatchBootstrap emptyBootstrap() {
        return WatchBootstrap.fromLists(List.of(), List.of(), List.of());
    }

    private static WatcherFixture newWatcherFixture() throws Exception {
        WatcherRegister register = new WatcherRegister();
        WatchClient watchClient = mock(WatchClient.class);
        when(watchClient.watch(any(Watcher.class))).thenReturn(mock(WebSocket.class));
        Libp2pEndpoint libp2pEndpoint = mock(Libp2pEndpoint.class);
        inject(register, "watchClient", watchClient);
        inject(register, "tunneler", mock(Tunneler.class));
        inject(register, "platformInjector", mock(PlatformInjector.class));
        inject(register, "logger", mock(ConnectLogger.class));
        inject(register, "api", mock(SimpleConnectApi.class));
        inject(register, "libp2pEndpoint", libp2pEndpoint);
        return new WatcherFixture(register, watchClient, libp2pEndpoint);
    }

    private static void inject(WatcherRegister register, String fieldName, Object value) throws Exception {
        Field field = WatcherRegister.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(register, value);
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class WatcherFixture {
        private final WatcherRegister register;
        private final WatchClient watchClient;
        private final Libp2pEndpoint libp2pEndpoint;

        private WatcherFixture(
                WatcherRegister register, WatchClient watchClient, Libp2pEndpoint libp2pEndpoint) {
            this.register = register;
            this.watchClient = watchClient;
            this.libp2pEndpoint = libp2pEndpoint;
        }
    }

    private static final class CapturedCallbacks {
        private Runnable ready;
    }
}
