package com.minekube.connect.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.util.Constants;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommonModuleTest {
    @TempDir Path tempDir;

    @Test
    void connectHttpClientSendsPluginVersionHeader() throws Exception {
        CommonModule module = new CommonModule(tempDir);
        PlatformUtils platformUtils = platformUtils();

        OkHttpClient client = module.connectOkHttpClient(
                module.defaultOkHttpClient(),
                platformUtils,
                "spigot",
                new SimpleConnectApi(mock(ConnectLogger.class)),
                module.connectToken()
        );

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();

            Request request = new Request.Builder()
                    .url(server.url("/watch"))
                    .build();
            try (Response ignored = client.newCall(request).execute()) {
                RecordedRequest recorded = server.takeRequest();

                assertEquals(Constants.VERSION, recorded.getHeader("Connect-Version"));
                assertEquals("spigot", recorded.getHeader("Connect-Platform"));
                assertEquals("test-server-impl", recorded.getHeaders().values("Connect-Platform").get(1));
            }
        }
    }

    @Test
    void connectTokenIsPersistedForAllConnectClients() throws Exception {
        CommonModule module = new CommonModule(tempDir);

        String token = module.connectToken();

        assertTrue(token.startsWith("T-"));
        assertEquals(token, module.connectToken());
        assertTrue(java.nio.file.Files.readString(tempDir.resolve("token.json")).contains(token));
    }

    @Test
    void watchHttpClientKeepsConnectHeadersAndUsesWebSocketLiveness() throws Exception {
        CommonModule module = new CommonModule(tempDir);
        PlatformUtils platformUtils = platformUtils();
        OkHttpClient connectClient = module.connectOkHttpClient(
                module.defaultOkHttpClient(),
                platformUtils,
                "spigot",
                new SimpleConnectApi(mock(ConnectLogger.class)),
                module.connectToken()
        );
        OkHttpClient watchClient = module.watchOkHttpClient(connectClient);

        assertEquals(0, watchClient.readTimeoutMillis());
        assertEquals(30_000, watchClient.pingIntervalMillis());
        assertEquals(0, connectClient.pingIntervalMillis());

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("ok"));
            server.start();

            Request request = new Request.Builder()
                    .url(server.url("/watch"))
                    .build();
            try (Response ignored = watchClient.newCall(request).execute()) {
                RecordedRequest recorded = server.takeRequest();

                assertEquals(Constants.VERSION, recorded.getHeader("Connect-Version"));
                assertEquals("spigot", recorded.getHeader("Connect-Platform"));
                assertEquals("test-server-impl", recorded.getHeaders().values("Connect-Platform").get(1));
            }
        }
    }

    private static PlatformUtils platformUtils() {
        PlatformUtils platformUtils = mock(PlatformUtils.class);
        when(platformUtils.authType()).thenReturn(PlatformUtils.AuthType.ONLINE);
        when(platformUtils.serverImplementationName()).thenReturn("test-server-impl");
        when(platformUtils.minecraftVersion()).thenReturn("test-minecraft-version");
        when(platformUtils.getPlayerCount()).thenReturn(7);
        return platformUtils;
    }
}
