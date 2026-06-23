package com.minekube.connect.qsnm;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class QsnmTelemetry {
    public static final String EVENT_CONNECT_JAVA_INTEGRATION_SUCCESS =
            "connect_java.integration_success.v1";

    public static final String PROP_SOURCE = "source";
    public static final String PROP_OCCURRED_AT = "occurred_at";
    public static final String PROP_NETWORK_KEY = "network_key";
    public static final String PROP_EVIDENCE_ID = "evidence_id";
    public static final String PROP_PLAN_STATE = "plan_state";
    public static final String PROP_INTEGRATION_FAMILY = "integration_family";
    public static final String PROP_INTEGRATION_VERSION = "integration_version";
    public static final String PROP_CONNECT_TRANSPORT = "connect_transport";
    public static final String PROP_PLAYER_COUNT_BUCKET = "player_count_bucket";
    public static final String PROP_SESSION_COUNT_BUCKET = "session_count_bucket";
    public static final String PROP_IDENTITY_CONFIDENCE = "identity_confidence";
    public static final String PROP_SCHEMA_VERSION = "schema_version";
    public static final String PROP_PRIVACY_POSTURE = "privacy_posture";
    public static final String PROP_QSNM_QUALIFYING_EVENT = "qsnm_qualifying_event";

    public static final List<String> CONNECT_JAVA_INTEGRATION_SUCCESS_ALLOWLIST =
            Collections.unmodifiableList(Arrays.asList(
                    PROP_SOURCE,
                    PROP_OCCURRED_AT,
                    PROP_NETWORK_KEY,
                    PROP_EVIDENCE_ID,
                    PROP_PLAN_STATE,
                    PROP_INTEGRATION_FAMILY,
                    PROP_INTEGRATION_VERSION,
                    PROP_CONNECT_TRANSPORT,
                    PROP_PLAYER_COUNT_BUCKET,
                    PROP_SESSION_COUNT_BUCKET,
                    PROP_IDENTITY_CONFIDENCE,
                    PROP_SCHEMA_VERSION,
                    PROP_PRIVACY_POSTURE,
                    PROP_QSNM_QUALIFYING_EVENT
            ));

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private QsnmTelemetry() {
    }

    public static String monthlyNetworkKey(byte[] secret, String canonicalNetworkFingerprint, Instant occurredAt) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(canonicalNetworkFingerprint, "canonicalNetworkFingerprint");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (secret.length == 0) {
            throw new IllegalArgumentException("qsnm secret must not be empty");
        }
        String fingerprint = canonicalNetworkFingerprint.trim();
        if (fingerprint.isEmpty()) {
            throw new IllegalArgumentException("canonical network fingerprint must not be empty");
        }
        String month = MONTH_FORMAT.format(occurredAt);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            mac.update(month.getBytes(StandardCharsets.UTF_8));
            return "qsnm_" + month + "_" + hex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute QSNM network key", e);
        }
    }

    public static Map<String, Object> connectJavaIntegrationSuccess(ConnectJavaIntegrationSuccess event) {
        Objects.requireNonNull(event, "event");
        if (event.occurredAt == null) {
            throw new IllegalArgumentException("occurred_at must be set");
        }
        if (isBlank(event.networkKey)) {
            throw new IllegalArgumentException("network_key must be set");
        }
        if (isBlank(event.evidenceId)) {
            throw new IllegalArgumentException("evidence_id must be set");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROP_SOURCE, defaultString(event.source, "connect-java"));
        properties.put(PROP_OCCURRED_AT, event.occurredAt.toString());
        properties.put(PROP_NETWORK_KEY, event.networkKey);
        properties.put(PROP_EVIDENCE_ID, event.evidenceId);
        properties.put(PROP_PLAN_STATE, event.planState);
        properties.put(PROP_INTEGRATION_FAMILY, defaultString(event.integrationFamily, "connect-java"));
        properties.put(PROP_INTEGRATION_VERSION, event.integrationVersion);
        properties.put(PROP_CONNECT_TRANSPORT, event.connectTransport);
        properties.put(PROP_PLAYER_COUNT_BUCKET, event.playerCountBucket);
        properties.put(PROP_SESSION_COUNT_BUCKET, event.sessionCountBucket);
        properties.put(PROP_IDENTITY_CONFIDENCE, defaultString(event.identityConfidence, "strong"));
        properties.put(PROP_SCHEMA_VERSION, 1);
        properties.put(PROP_PRIVACY_POSTURE, "hmac_monthly_network_key_no_raw_host_ip_domain_email_config");
        properties.put(PROP_QSNM_QUALIFYING_EVENT, true);
        validateAllowlist(properties);
        return Collections.unmodifiableMap(properties);
    }

    private static void validateAllowlist(Map<String, Object> properties) {
        for (String key : properties.keySet()) {
            if (!CONNECT_JAVA_INTEGRATION_SUCCESS_ALLOWLIST.contains(key)) {
                throw new IllegalArgumentException("property is not in QSNM allowlist: " + key);
            }
        }
    }

    private static String defaultString(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    public static final class ConnectJavaIntegrationSuccess {
        private final String source;
        private final Instant occurredAt;
        private final String networkKey;
        private final String evidenceId;
        private final String planState;
        private final String integrationFamily;
        private final String integrationVersion;
        private final String connectTransport;
        private final String playerCountBucket;
        private final String sessionCountBucket;
        private final String identityConfidence;

        public ConnectJavaIntegrationSuccess(
                String source,
                Instant occurredAt,
                String networkKey,
                String evidenceId,
                String planState,
                String integrationFamily,
                String integrationVersion,
                String connectTransport,
                String playerCountBucket,
                String sessionCountBucket,
                String identityConfidence) {
            this.source = source;
            this.occurredAt = occurredAt;
            this.networkKey = networkKey;
            this.evidenceId = evidenceId;
            this.planState = planState;
            this.integrationFamily = integrationFamily;
            this.integrationVersion = integrationVersion;
            this.connectTransport = connectTransport;
            this.playerCountBucket = playerCountBucket;
            this.sessionCountBucket = sessionCountBucket;
            this.identityConfidence = identityConfidence;
        }
    }
}
