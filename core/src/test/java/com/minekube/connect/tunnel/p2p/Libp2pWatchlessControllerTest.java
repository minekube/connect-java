package com.minekube.connect.tunnel.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Libp2pWatchlessControllerTest {
    @Test
    void registrationAndHealthyStatusEnterWatchlessOnce() {
        Counter ready = new Counter();
        Counter fallback = new Counter();
        Libp2pWatchlessController controller = new Libp2pWatchlessController(
                true, 3, ready::increment, fallback::increment);

        controller.registrationCommitted(true);
        controller.statusReportResult(true);

        assertEquals(1, ready.count);
        assertEquals(0, fallback.count);
    }

    @Test
    void registrationCloseFallsBackAfterWatchlessReady() {
        Counter ready = new Counter();
        Counter fallback = new Counter();
        Libp2pWatchlessController controller = new Libp2pWatchlessController(
                true, 3, ready::increment, fallback::increment);

        controller.registrationCommitted(true);
        controller.registrationClosed();

        assertEquals(1, ready.count);
        assertEquals(1, fallback.count);
    }

    @Test
    void repeatedStatusFailuresFallBackAfterThreshold() {
        Counter ready = new Counter();
        Counter fallback = new Counter();
        Libp2pWatchlessController controller = new Libp2pWatchlessController(
                true, 3, ready::increment, fallback::increment);

        controller.registrationCommitted(true);
        controller.statusReportResult(false);
        controller.statusReportResult(false);
        controller.statusReportResult(false);

        assertEquals(1, ready.count);
        assertEquals(1, fallback.count);
    }

    @Test
    void staleHealthyStatusAfterRegistrationCloseDoesNotReenterWatchless() {
        Counter ready = new Counter();
        Counter fallback = new Counter();
        Libp2pWatchlessController controller = new Libp2pWatchlessController(
                true, 3, ready::increment, fallback::increment);

        controller.registrationCommitted(true);
        controller.registrationClosed();
        controller.statusReportResult(true);

        assertEquals(1, ready.count);
        assertEquals(1, fallback.count);
    }

    @Test
    void unsupportedWatchlessDoesNotNotify() {
        Counter ready = new Counter();
        Counter fallback = new Counter();
        Libp2pWatchlessController controller = new Libp2pWatchlessController(
                false, 3, ready::increment, fallback::increment);

        controller.registrationCommitted(true);
        controller.statusReportResult(false);
        controller.registrationClosed();

        assertEquals(0, ready.count);
        assertEquals(0, fallback.count);
    }

    private static final class Counter {
        private int count;

        private void increment() {
            count++;
        }
    }
}
