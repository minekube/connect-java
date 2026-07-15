package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minekube.connect.config.ConnectConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WatchClientTest {
    @Test
    void defaultDisabledConfigurationDoesNotAdvertiseBedrockIdentity() {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        WatchClient client = new WatchClient(httpClient, new ConnectConfig());

        client.watch(new Watcher() {
            @Override public void onOpen(WatchBootstrap bootstrap) { }
            @Override public void onProposal(SessionProposal proposal) { }
            @Override public void onCompleted() { }
            @Override public void onError(Throwable throwable) { }
        });

        ArgumentCaptor<Request> request = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newWebSocket(request.capture(), any(WebSocketListener.class));
        assertFalse(request.getValue().headers("Connect-Capabilities")
                .contains("bedrock-identity-v1"));
    }
}
