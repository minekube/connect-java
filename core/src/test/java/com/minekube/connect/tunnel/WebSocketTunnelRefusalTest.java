package com.minekube.connect.tunnel;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** Which tunnel service refusals mean "this session id is gone for good". */
class WebSocketTunnelRefusalTest {
    @Test
    void goneIsAnAbandonedSession() throws Exception {
        Outcome outcome = refuse(new MockResponse().setResponseCode(410));

        assertEquals(410, outcome.abandonedStatus.get());
        assertNull(outcome.error.get());
    }

    @Test
    void notFoundReportingAReleasedPendingEntryIsAnAbandonedSession() throws Exception {
        Outcome outcome = refuse(new MockResponse()
                .setResponseCode(404)
                .setBody("tunnel session not found; connector version 0.15.18; requested Fly instance a; current Fly instance b; pending metadata released request_kind=join protocol=java age=4.1s released_ago=30ms; likely causes: expired session, released session (the dial ended before this request arrived), duplicate tunnel, stale connector, or tunnel request reached the wrong instance"));

        assertEquals(404, outcome.abandonedStatus.get());
        assertNull(outcome.error.get());
    }

    @Test
    void notFoundWithAnyOtherPendingStateNeverLatchesDespiteTheStaticCausesText() throws Exception {
        for (String state : new String[] {"absent", "pending", "consumed"}) {
            Outcome outcome = refuse(new MockResponse()
                    .setResponseCode(404)
                    .setBody("tunnel session not found; connector version 0.15.18; requested Fly instance a; current Fly instance b; pending metadata "+state+" request_kind=join protocol=java age=4.1s released_ago=30ms; likely causes: expired session, released session (the dial ended before this request arrived), duplicate tunnel, stale connector, or tunnel request reached the wrong instance"));

            assertNotNull(outcome.error.get(), "pending metadata " + state + " is an ordinary dial failure");
            assertEquals(0, outcome.abandonedStatus.get(), "pending metadata " + state + " must not latch");
        }
    }

    @Test
    void bareNotFoundIsATransportFailureAndNeverLatches() throws Exception {
        Outcome outcome = refuse(new MockResponse().setResponseCode(404).setBody("no route"));

        assertNotNull(outcome.error.get(), "a bare 404 is reported as an ordinary dial failure");
        assertEquals(0, outcome.abandonedStatus.get(),
                "a 404 from a load balancer or a wrong route must not abandon the session");
    }

    @Test
    void emptyNotFoundIsATransportFailureAndNeverLatches() throws Exception {
        Outcome outcome = refuse(new MockResponse().setResponseCode(404));

        assertNotNull(outcome.error.get());
        assertEquals(0, outcome.abandonedStatus.get());
    }

    private static Outcome refuse(MockResponse response) throws Exception {
        OkHttpClient client = new OkHttpClient();
        Outcome outcome = new Outcome();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(response);
            server.start();
            String url = server.url("/tunnel").toString().replaceFirst("^http:", "ws:");

            new Tunneler(new WebSocketTunnelTransport(client), new SessionDialGuard())
                    .tunnel(url, "s-1", outcome);

            await().atMost(5, SECONDS).until(outcome.closed::get);
            return outcome;
        } finally {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    private static final class Outcome implements TunnelConn.Handler {
        private final AtomicInteger abandonedStatus = new AtomicInteger();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public void onReceive(byte[] data) {
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onSessionAbandoned(int httpStatus) {
            abandonedStatus.set(httpStatus);
        }

        @Override
        public void onClose() {
            closed.set(true);
        }
    }
}
