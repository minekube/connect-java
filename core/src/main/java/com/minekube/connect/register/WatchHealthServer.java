/*
 * Copyright (c) 2026 Minekube. https://minekube.com
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

package com.minekube.connect.register;

import com.google.inject.Inject;
import com.minekube.connect.api.logger.ConnectLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

public class WatchHealthServer {
    private static final String HEALTH_ADDR_ENV = "CONNECT_WATCH_HEALTH_ADDR";
    private static final String HEALTH_PATH = "/healthz";

    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;

    @Inject
    public WatchHealthServer(WatcherRegister watcherRegister, ConnectLogger logger) throws IOException {
        this(System.getenv(HEALTH_ADDR_ENV), watcherRegister::isHealthy, logger);
    }

    WatchHealthServer(String address, BooleanSupplier healthy, ConnectLogger logger) throws IOException {
        if (address == null || address.trim().isEmpty()) {
            server = null;
            executor = null;
            baseUrl = null;
            return;
        }

        InetSocketAddress socketAddress = parseAddress(address);
        server = HttpServer.create(socketAddress, 0);
        executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
        baseUrl = "http://" + address;

        server.createContext("/", exchange -> handle(exchange, healthy));
        server.setExecutor(executor);
        server.start();
        logger.info("Connect watcher health endpoint listening on " + address);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    String url(String path) {
        return baseUrl + path;
    }

    private static void handle(HttpExchange exchange, BooleanSupplier healthy) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                write(exchange, 405, "method not allowed\n");
                return;
            }
            if (!HEALTH_PATH.equals(exchange.getRequestURI().getPath())) {
                write(exchange, 404, "not found\n");
                return;
            }

            boolean isHealthy = healthy.getAsBoolean();
            write(exchange, isHealthy ? 200 : 503, isHealthy ? "ok\n" : "unhealthy\n");
        } finally {
            exchange.close();
        }
    }

    private static void write(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static InetSocketAddress parseAddress(String address) {
        int separator = address.lastIndexOf(':');
        if (separator <= 0 || separator == address.length() - 1) {
            throw new IllegalArgumentException(
                    HEALTH_ADDR_ENV + " must be in host:port form, for example 127.0.0.1:8087");
        }

        String host = address.substring(0, separator);
        int port = Integer.parseInt(address.substring(separator + 1));
        return new InetSocketAddress(host, port);
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "connect-watch-health");
            thread.setDaemon(true);
            return thread;
        }
    }
}
