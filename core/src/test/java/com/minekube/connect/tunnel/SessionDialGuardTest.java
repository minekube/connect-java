package com.minekube.connect.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SessionDialGuardTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final SessionDialGuard guard = new SessionDialGuard(
            Duration.ofMillis(100), 4, now::get);

    @Test
    void firstDeliveryOwnsTheSessionAndRepeatsAreAbsorbed() {
        assertTrue(guard.tryAcceptDelivery("session-1"));
        assertFalse(guard.tryAcceptDelivery("session-1"));
        assertFalse(guard.tryAcceptDelivery("session-1"));
        assertTrue(guard.tryAcceptDelivery("session-2"), "a different session id is unaffected");
    }

    @Test
    void abandonedSessionIsNeverAcceptedAgain() {
        assertTrue(guard.tryAcceptDelivery("session-1"));

        guard.abandon("session-1");

        assertTrue(guard.isAbandoned("session-1"));
        assertTrue(guard.tryAcceptDelivery("session-1"),
                "an abandoned session is let through so the caller can reject it; the latch stays");
        assertTrue(guard.isAbandoned("session-1"), "accepting a delivery must not clear the latch");
        assertFalse(guard.isAbandoned("session-2"), "abandonment is per session id");
    }

    @Test
    void abandonBeforeAnyDeliveryIsHonoured() {
        guard.abandon("session-1");

        assertTrue(guard.isAbandoned("session-1"));
        assertTrue(guard.tryAcceptDelivery("session-1"));
        assertTrue(guard.isAbandoned("session-1"));
    }

    @Test
    void releasedClaimServesTheRedelivery() {
        assertTrue(guard.tryAcceptDelivery("session-1"));

        guard.release("session-1");

        assertTrue(guard.tryAcceptDelivery("session-1"),
                "a rejected or failed delivery must not block the edge's redelivery");
        assertFalse(guard.tryAcceptDelivery("session-1"));
    }

    @Test
    void releaseNeverClearsAnAbandonedSession() {
        assertTrue(guard.tryAcceptDelivery("session-1"));
        guard.abandon("session-1");

        guard.release("session-1");

        assertTrue(guard.isAbandoned("session-1"));
    }

    @Test
    void entriesExpireAfterTheRetentionWindow() {
        assertTrue(guard.tryAcceptDelivery("session-1"));
        guard.abandon("session-2");

        now.addAndGet(101);

        assertFalse(guard.isAbandoned("session-2"), "the abandonment latch is bounded");
        assertTrue(guard.tryAcceptDelivery("session-1"), "an expired claim no longer blocks a dial");
        assertTrue(guard.tryAcceptDelivery("session-2"));
    }

    @Test
    void trackedEntriesStayBounded() {
        for (int i = 0; i < 50; i++) {
            assertTrue(guard.tryAcceptDelivery("session-" + i));
        }

        assertEquals(4, guard.trackedSessions(), "a long-running proxy must not grow the map");
        assertFalse(guard.tryAcceptDelivery("session-49"), "the newest entry is still claimed");
        assertTrue(guard.tryAcceptDelivery("session-0"), "the evicted entry is claimable again");
    }

    @Test
    void concurrentClaimsAndAbandonmentsStayExactAndBounded() throws Exception {
        int maxEntries = 64;
        int threads = 16;
        int sessions = 40; // fewer than maxEntries, so no claim is evicted mid-test
        SessionDialGuard shared = new SessionDialGuard(Duration.ofMinutes(1), maxEntries, now::get);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger[] winners = new AtomicInteger[sessions];
        for (int i = 0; i < sessions; i++) {
            winners[i] = new AtomicInteger();
        }
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < sessions; i++) {
                        if (shared.tryAcceptDelivery("session-" + i)) {
                            winners[i].incrementAndGet();
                        }
                    }
                    for (int i = 0; i < sessions; i++) {
                        shared.abandon("session-" + i);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // A claim that races an abandon may be accepted again (an abandoned id is let through),
        // but every id was claimed at least once and is latched at the end.
        for (int i = 0; i < sessions; i++) {
            assertTrue(winners[i].get() >= 1, "session-" + i + " was never accepted");
            assertTrue(shared.isAbandoned("session-" + i), "session-" + i + " must end latched");
        }
        assertEquals(sessions, shared.trackedSessions());

        for (int i = 0; i < 10_000; i++) {
            shared.tryAcceptDelivery("flood-" + i);
        }
        assertEquals(maxEntries, shared.trackedSessions(), "the guard must stay bounded");
    }

    @Test
    void concurrentClaimsOfOneSessionHaveExactlyOneWinner() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await();
                    if (guard.tryAcceptDelivery("contended")) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "one session id must carry exactly one dial");
    }

    @Test
    void emptySessionIdIsNotTracked() {
        assertTrue(guard.tryAcceptDelivery(""));
        assertTrue(guard.tryAcceptDelivery(""), "an unaddressable id is left to the transport checks");
        assertFalse(guard.isAbandoned(""));
        guard.abandon("");
        assertEquals(0, guard.trackedSessions());
    }

    @Test
    void rejectsUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionDialGuard(Duration.ZERO, 1, now::get));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionDialGuard(Duration.ofSeconds(1), 0, now::get));
    }
}
