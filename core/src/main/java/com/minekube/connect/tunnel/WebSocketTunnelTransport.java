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

package com.minekube.connect.tunnel;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.minekube.connect.tunnel.TunnelConn.Handler;
import com.minekube.connect.util.ReflectionUtils;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport.Type;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WebSocketTunnelTransport implements TunnelClientTransport {

    private static final String SESSION_HEADER = "Connect-Session";
    private static final long MAX_REFUSAL_BODY_BYTES = 1024;
    private static final Field DATA = ReflectionUtils.getField(ByteString.class, "data");
    private final OkHttpClient httpClient;

    @Inject
    public WebSocketTunnelTransport(@Named("connectHttpClient") OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Type type() {
        return Type.TYPE_WEBSOCKET;
    }

    @Override
    public TunnelConn tunnel(final String tunnelServiceAddr, String sessionId, Handler handler) {
        checkNotNull(tunnelServiceAddr, "tunnelServiceAddr must not be null");
        checkNotNull(sessionId, "sessionId must not be null");
        checkNotNull(handler, "handler must not be null");
        checkArgument(!tunnelServiceAddr.isEmpty(),
                "tunnelServiceAddr must not be empty");
        checkArgument(!sessionId.isEmpty(), "sessionId must not be empty");

        Request.Builder request = new Request.Builder()
                .url(tunnelServiceAddr)
                .addHeader(SESSION_HEADER, sessionId);

        AtomicBoolean closeHandlerOnce = new AtomicBoolean();
        Runnable handlerOnClose = () -> {
            if (closeHandlerOnce.compareAndSet(false, true)) {
                handler.onClose();
            }
        };

        AtomicBoolean opened = new AtomicBoolean();

        WebSocket ws = httpClient.newWebSocket(request.build(), new WebSocketListener() {
            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                handlerOnClose.run();
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t,
                                  @Nullable Response response) {
                if (isSessionAbandoned(response)) {
                    // The tunnel service answered the upgrade with "session gone": the
                    // session's pending entry is gone for good, so this is not a transport
                    // failure and must not be retried or logged as an internal error.
                    handler.onSessionAbandoned(response.code());
                    handlerOnClose.run();
                    return;
                }
                if (!(t instanceof EOFException)) {
                    handler.onError(t);
                }
                handlerOnClose.run();
            }


            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull ByteString bytes) {
                // handler.onReceive(bytes.toByteArray()); // would copy

                // Zero-copy fast path with safe fallback. DATA is null if the field
                // can't be located (e.g. after Okio relocation); getCastedValue can
                // also return null at runtime because IllegalAccessException is
                // swallowed inside ReflectionUtils.getValue. Without this guard,
                // onReceive(null) flows into Unpooled.wrappedBuffer(null), NPE-ing
                // the OkHttp dispatcher thread and dropping every tunnel on it.
                byte[] rawBytes = DATA != null ? ReflectionUtils.getCastedValue(bytes, DATA) : null;
                if (rawBytes == null) {
                    rawBytes = bytes.toByteArray();
                }
                handler.onReceive(rawBytes);
            }

            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                opened.set(true);
                // TODO log connected(?)
            }
        });

        return new TunnelConn() {
            @Override
            public void write(byte[] data) {
                ws.send(ByteString.of(data));
            }

            @Override
            public void close(Throwable t) {
                if (t == null) {
                    ws.close(1000, "tunnel closed clientside");
                } else {
                    ws.close(1002, t.toString());
                }
                handlerOnClose.run();
            }

            @Override
            public boolean opened() {
                return opened.get();
            }
        };
    }

    /**
     * The tunnel service answers a tunnel request whose pending session is gone either with
     * {@code 410 Gone} or with a {@code 404} whose body reports {@code pending metadata released}
     * (moxy's missing-session path: "tunnel session not found; ...; pending metadata released
     * request_kind=...; likely causes: ..."). Both mean "this session id will never be dialable
     * again", which is categorically different from a transport failure.
     *
     * <p>The body's trailing "likely causes" list always mentions "released session", so only the
     * {@code pending metadata <state>} field is authoritative: {@code absent}, {@code pending} and
     * {@code consumed} (wrong instance, expired, duplicate tunnel) must not latch, and neither must
     * a bare 404 from a load balancer or a wrong route, which could block a session that is still
     * dialable elsewhere.
     */
    private static final java.util.regex.Pattern RELEASED_PENDING_METADATA =
            java.util.regex.Pattern.compile("pending metadata released(\\s|;|$)");

    private static boolean isSessionAbandoned(@Nullable Response response) {
        if (response == null) {
            return false;
        }
        int code = response.code();
        if (code == 410) {
            return true;
        }
        if (code != 404) {
            return false;
        }
        try {
            String body = response.peekBody(MAX_REFUSAL_BODY_BYTES).string().toLowerCase(Locale.ROOT);
            return body.contains("tunnel session not found")
                    && RELEASED_PENDING_METADATA.matcher(body).find();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        // cancel queued connections
        Stream.of(httpClient.dispatcher().queuedCalls())
                .flatMap(Collection::stream)
                .filter(call -> call.request().header(SESSION_HEADER) != null)
                .forEach(Call::cancel);
    }
}
