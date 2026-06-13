package com.minekube.connect.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.logger.ConnectLogger;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
