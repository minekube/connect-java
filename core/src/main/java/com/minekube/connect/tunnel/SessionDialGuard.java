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

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Remembers, per session id, that the connector already owns the session's one dial.
 *
 * <p>Two failure modes are prevented, both measured on the fleet:
 *
 * <ul>
 *   <li><b>Two dials for one session id.</b> A session proposal is delivered more than once for
 *       the same id (the edge's unanswered-proposal retry every {@code proposalAnswerTimeout}, a
 *       watch fail-over re-publish, or a transport-level retry). Every delivery used to open its
 *       own {@code LocalSession} and therefore its own tunnel. The edge's invariant allows one
 *       tunnel per session, so the repeats must be absorbed. {@link #tryAcceptDelivery(String)}
 *       claims the id; a repeat delivery is dropped instead of dialled.</li>
 *   <li><b>Dialling after the edge gave up.</b> When the tunnel service answers the dial with
 *       "session not found" (its pending entry was released, expired, or claimed elsewhere) the
 *       session is gone for good - the edge never re-arms a released entry - so every later dial
 *       for that id is guaranteed to fail. {@link #abandon(String)} latches that, and
 *       {@link #isAbandoned(String)} is what {@link Tunneler} refuses to dial through.</li>
 * </ul>
 *
 * <p>Session ids are single use, so entries are retained for a bounded window instead of being
 * released: {@link #DEFAULT_RETENTION} comfortably exceeds one attempt - the edge's answer budget
 * (2 x 3 s) plus the connector's 10 s local connect timeout plus a dial - and the map is capped at
 * {@link #DEFAULT_MAX_ENTRIES} with oldest-first eviction, so a long-running proxy cannot grow it
 * without bound. The connector never guesses a lifetime from its own clock: an id it has not been
 * told about is dialled normally.
 */
public final class SessionDialGuard {
    /** Edge answer budget (2 x 3 s) + local connect timeout (10 s) + dial, with head-room. */
    static final Duration DEFAULT_RETENTION = Duration.ofSeconds(30);
    static final int DEFAULT_MAX_ENTRIES = 8192;

    private final long retentionMillis;
    private final int maxEntries;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public SessionDialGuard() {
        this(DEFAULT_RETENTION, DEFAULT_MAX_ENTRIES, System::currentTimeMillis);
    }

    SessionDialGuard(Duration retention, int maxEntries, LongSupplier clock) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.retentionMillis = retention.toMillis();
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /**
     * Claims a session proposal delivery.
     *
     * @param sessionId the proposed session id
     * @return {@code true} when this delivery owns the session (it may dial), {@code false} when a
     *         delivery is already in flight or the session was abandoned and the delivery must be
     *         dropped without dialling
     */
    public synchronized boolean tryAcceptDelivery(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            // Not addressable: the tunnel transports reject an empty id on their own.
            return true;
        }
        long now = clock.getAsLong();
        if (live(sessionId, now) != null) {
            return false;
        }
        put(sessionId, Entry.State.IN_FLIGHT, now);
        return true;
    }

    /**
     * Latches that the tunnel service no longer knows this session, so it may never be dialled
     * again inside the retention window.
     */
    public synchronized void abandon(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return;
        }
        put(sessionId, Entry.State.ABANDONED, clock.getAsLong());
    }

    /** Whether the tunnel service has already abandoned this session. */
    public synchronized boolean isAbandoned(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        Entry entry = live(sessionId, clock.getAsLong());
        return entry != null && entry.state == Entry.State.ABANDONED;
    }

    /** Number of live entries. Bounded by {@link #DEFAULT_MAX_ENTRIES}; exposed for tests. */
    synchronized int trackedSessions() {
        entries.values().removeIf(entry -> entry.isExpired(clock.getAsLong()));
        return entries.size();
    }

    private Entry live(String sessionId, long now) {
        Entry entry = entries.get(sessionId);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(now)) {
            entries.remove(sessionId);
            return null;
        }
        return entry;
    }

    private void put(String sessionId, Entry.State state, long now) {
        entries.put(sessionId, new Entry(state, now + retentionMillis));
        pruneExpired(now);
        if (entries.size() > maxEntries) {
            // Oldest insertion goes first: the oldest session is the least likely to be dialled.
            Iterator<String> oldest = entries.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    private void pruneExpired(long now) {
        entries.values().removeIf(entry -> entry.isExpired(now));
    }

    private static final class Entry {
        enum State {
            IN_FLIGHT,
            ABANDONED
        }

        private final State state;
        private final long expiresAtMillis;

        private Entry(State state, long expiresAtMillis) {
            this.state = state;
            this.expiresAtMillis = expiresAtMillis;
        }

        private boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }
}
