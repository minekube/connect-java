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

package com.minekube.connect.watch;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.protobuf.InvalidProtocolBufferException;
import com.minekube.connect.config.ConnectConfig;
import java.io.IOException;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionRejection;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionRejection.Builder;
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchRequest;
import minekube.connect.v1alpha1.WatchServiceOuterClass.WatchResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WatchClient {
    private static final String ENDPOINT_HEADER = "Connect-Endpoint";
    private static final String ENDPOINT_OFFLINE_MODE_HEADER = ENDPOINT_HEADER + "-Offline-Mode";
    private static final String ENDPOINT_PARENTS_HEADER = ENDPOINT_HEADER + "-Parents";
    private static final String WATCH_URL = System.getenv().getOrDefault(
            "CONNECT_WATCH_URL", "wss://watch-connect.minekube.net");

    private final OkHttpClient httpClient;
    private final ConnectConfig config;

    @Inject
    public WatchClient(@Named("connectHttpClient") OkHttpClient httpClient, ConnectConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    public WebSocket watch(Watcher watcher) {
        Request.Builder request = new Request.Builder()
                .url(WATCH_URL)
                .header(ENDPOINT_HEADER, config.getEndpoint());

        if (config.getAllowOfflineModePlayers() != null) {
            request = request.header(ENDPOINT_OFFLINE_MODE_HEADER,
                    config.getAllowOfflineModePlayers().toString());
        }
        if (config.getSuperEndpoints() != null) {
            for (String superEndpoint : config.getSuperEndpoints()) {
                request = request.addHeader(ENDPOINT_PARENTS_HEADER, superEndpoint);
            }
        }

        Request req = request.build();
        return httpClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                if (code != 1000) {
                    watcher.onError(
                            new RuntimeException("Watch closed with code " + code + ": " + reason));
                    return;
                }
                watcher.onCompleted();
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t,
                                  @Nullable Response response) {

                // Try to get a more detailed error message from the server
                String message = null;
                if (response != null && response.body() != null) {
                    try {
                        message = response.body().string();
                    } catch (IOException ignored) {
                    }
                }

                if (message == null || message.isEmpty()) {
                    watcher.onError(t);
                } else {
                    watcher.onError(new RuntimeException(message, t));
                }
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                WatchResponse res;
                try {
                    res = WatchResponse.parseFrom(bytes.asByteBuffer());
                } catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                    webSocket.close(1002, e.toString());
                    return;
                }

                SessionProposal prop = new SessionProposal(
                        res.getSession(),
                        reason -> {
                            Builder rejection = SessionRejection.newBuilder()
                                    .setId(res.getSession().getId());
                            if (reason != null) {
                                rejection.setReason(reason);
                            }
                            webSocket.send(ByteString.of(WatchRequest.newBuilder()
                                    .setSessionRejection(rejection)
                                    .build()
                                    .toByteArray()
                            ));
                        }
                );
                watcher.onProposal(prop);
            }

            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                watcher.onOpen();
            }
        });

    }

}
