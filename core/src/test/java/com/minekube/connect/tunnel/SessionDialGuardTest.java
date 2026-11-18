package com.minekube.connect.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
        assertFalse(guard.tryAcceptDelivery("session-1"),
                "a re-proposal of an abandoned session must not be dialled");
        assertFalse(guard.isAbandoned("session-2"), "abandonment is per session id");
    }

    @Test
    void abandonBeforeAnyDeliveryIsHonoured() {
        guard.abandon("session-1");

        assertTrue(guard.isAbandoned("session-1"));
        assertFalse(guard.tryAcceptDelivery("session-1"));
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
