package com.minekube.connect.qsnm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QsnmTelemetryTest {
    @Test
    void monthlyNetworkKeyRotatesByUtcMonth() {
        byte[] secret = "metric-secret".getBytes(StandardCharsets.UTF_8);
        String fingerprint = "endpoint-instance:abc123";

        String june = QsnmTelemetry.monthlyNetworkKey(
                secret, fingerprint, Instant.parse("2026-06-30T23:59:00Z"));
        String july = QsnmTelemetry.monthlyNetworkKey(
                secret, fingerprint, Instant.parse("2026-07-01T00:00:00Z"));
        String again = QsnmTelemetry.monthlyNetworkKey(
                secret, fingerprint, Instant.parse("2026-06-01T00:00:00Z"));

        assertNotEquals(june, july);
        assertEquals(june, again);
        assertTrue(june.startsWith("qsnm_2026-06_"));
        assertEquals("qsnm_2026-06_".length() + 64, june.length());
    }

    @Test
    void connectJavaIntegrationSuccessPropertiesAreAllowlisted() {
        Map<String, Object> properties = QsnmTelemetry.connectJavaIntegrationSuccess(
                new QsnmTelemetry.ConnectJavaIntegrationSuccess(
                        null,
                        Instant.parse("2026-06-23T13:00:00Z"),
                        "qsnm_2026-06_hash",
                        "session:test-session",
                        "free",
                        null,
                        "1.2.3",
                        "libp2p",
                        "1-5",
                        "1",
                        null
                ));

        for (String key : properties.keySet()) {
            assertTrue(QsnmTelemetry.CONNECT_JAVA_INTEGRATION_SUCCESS_ALLOWLIST.contains(key), key);
        }
        for (String forbidden : new String[] {"ip", "host", "domain", "email", "config"}) {
            assertFalse(properties.containsKey(forbidden), forbidden);
        }
        assertEquals(true, properties.get(QsnmTelemetry.PROP_QSNM_QUALIFYING_EVENT));
        assertEquals("connect-java", properties.get(QsnmTelemetry.PROP_SOURCE));
        assertEquals("connect-java", properties.get(QsnmTelemetry.PROP_INTEGRATION_FAMILY));
        assertEquals("strong", properties.get(QsnmTelemetry.PROP_IDENTITY_CONFIDENCE));
    }
}
