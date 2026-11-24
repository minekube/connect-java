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

import com.google.inject.Inject;
import com.minekube.connect.tunnel.TunnelConn.Handler;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport.Type;

public class Tunneler implements Closeable {

    private final Map<Type, TunnelClientTransport> transports;
    private final SessionDialGuard dialGuard;

    @Inject
    public Tunneler(Set<TunnelClientTransport> transports, SessionDialGuard dialGuard) {
        this.transports = new EnumMap<>(Type.class);
        for (TunnelClientTransport transport : transports) {
            this.transports.put(transport.type(), transport);
        }
        this.dialGuard = dialGuard;
    }

    public Tunneler(TunnelClientTransport transport, SessionDialGuard dialGuard) {
        this(Collections.singleton(transport), dialGuard);
    }

    /**
     * Session ids this tunneler has already taken a proposal for and will not dial again once the
     * tunnel service abandoned them.
     */
    public SessionDialGuard dialGuard() {
        return dialGuard;
    }

    public void prepare(Session session) {
        for (SelectedTransport selected : select(session)) {
            try {
                selected.transport.prepare(selected.address);
            } catch (RuntimeException ignored) {
                // Warmup must not prevent the regular tunnel fallback chain.
            }
        }
    }

    public TunnelConn tunnel(final String tunnelServiceAddr, String sessionId, Handler handler) {
        checkNotAbandoned(sessionId);
        TunnelClientTransport transport = transports.get(Type.TYPE_WEBSOCKET);
        if (transport == null) {
            throw new IllegalStateException("no websocket tunnel transport configured");
        }
        return transport.tunnel(tunnelServiceAddr, sessionId, handler);
    }

    public TunnelConn tunnel(Session session, Handler handler) {
        checkNotAbandoned(session.getId());
        RuntimeException lastFailure = null;
        for (SelectedTransport selected : select(session)) {
            try {
                return selected.transport.tunnel(selected.address, session.getId(), handler);
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IllegalStateException("no compatible tunnel transport configured");
    }

    private void checkNotAbandoned(String sessionId) {
        if (dialGuard.isAbandoned(sessionId)) {
            throw new SessionAbandonedException(sessionId);
        }
    }

    @Override
    public void close() {
        for (TunnelClientTransport transport : transports.values()) {
            transport.close();
        }
    }

    private List<SelectedTransport> select(Session session) {
        List<SelectedTransport> selected = new ArrayList<>();
        addAdvertisedTransport(session, selected, Type.TYPE_LIBP2P);
        addAdvertisedTransport(session, selected, Type.TYPE_WEBSOCKET);

        TunnelClientTransport websocket = transports.get(Type.TYPE_WEBSOCKET);
        if (websocket != null && !session.getTunnelServiceAddr().isEmpty()) {
            selected.add(new SelectedTransport(websocket, session.getTunnelServiceAddr()));
        }
        return selected;
    }

    private void addAdvertisedTransport(
            Session session,
            List<SelectedTransport> selected,
            Type type
    ) {
        TunnelClientTransport transport = transports.get(type);
        if (transport == null) {
            return;
        }
        for (TunnelTransport tunnelTransport : session.getTunnelTransportsList()) {
            if (tunnelTransport.getType() == type && !tunnelTransport.getAddress().isEmpty()) {
                selected.add(new SelectedTransport(transport, tunnelTransport.getAddress()));
            }
        }
    }

    /**
     * Thrown instead of dialling when the tunnel service already abandoned the session.
     *
     * <p>The edge never re-arms a released pending entry, so the dial could only ever produce a
     * tunnel that is upgraded and closed again. Callers reject the proposal with a player-safe
     * status rather than reporting an internal error.
     */
    public static final class SessionAbandonedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String sessionId;

        SessionAbandonedException(String sessionId) {
            super("session " + sessionId + " was abandoned by the tunnel service");
            this.sessionId = sessionId;
        }

        public String sessionId() {
            return sessionId;
        }
    }

    private static final class SelectedTransport {
        private final TunnelClientTransport transport;
        private final String address;

        private SelectedTransport(TunnelClientTransport transport, String address) {
            this.transport = transport;
            this.address = address;
        }
    }
}
