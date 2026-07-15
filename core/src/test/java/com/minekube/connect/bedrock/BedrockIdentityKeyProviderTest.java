package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.minekube.connect.config.ConnectConfig;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class BedrockIdentityKeyProviderTest {
    @Test
    void returnsStaticConfiguredKeys() {
        byte[] first = filledKey((byte) 1);
        byte[] second = filledKey((byte) 2);
        ConnectConfig config = config("");
        setField(config.getBedrockIdentity(), "publicKey", Base64.getEncoder().encodeToString(first));
        setField(config.getBedrockIdentity(), "publicKeys",
                List.of(Base64.getEncoder().encodeToString(second)));

        BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(config, new OkHttpClient());

        assertKeys(provider.keys(), first, second);
    }

    @Test
    void rejectsStaticKeyThatTheVerifierCannotParse() {
        ConnectConfig staticConfig = config("");
        setField(staticConfig.getBedrockIdentity(), "publicKey",
                Base64.getEncoder().encodeToString(new byte[44]));
        assertTrue(new BedrockIdentityKeyProvider(staticConfig, new OkHttpClient()).keys().isEmpty());
    }

    @Test
    void fetchesCurrentAndPreviousMetadataKeys() throws Exception {
        byte[] current = filledKey((byte) 3);
        byte[] previous = filledKey((byte) 4);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{"
                            + "\"issuer\":\"minekube-connect\","
                            + "\"algorithm\":\"Ed25519\","
                            + "\"current_public_key\":\"" + Base64.getEncoder().encodeToString(current) + "\","
                            + "\"previous_public_keys\":[\"" + Base64.getEncoder().encodeToString(previous) + "\"],"
                            + "\"cache_max_age_seconds\":120"
                            + "}"));
            ConnectConfig config = config(server.url("/.well-known/minekube-connect/bedrock-identity-keys.json").toString());

            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(config, new OkHttpClient());

            assertKeys(provider.keys(), current, previous);
            assertEquals("/.well-known/minekube-connect/bedrock-identity-keys.json", server.takeRequest().getPath());
        }
    }

    @Test
    void failsClosedWhenConfiguredMetadataCannotBeInitiallyValidated() throws Exception {
        byte[] fallback = filledKey((byte) 5);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            ConnectConfig config = config(server.url("/keys.json").toString());
            setField(config.getBedrockIdentity(), "publicKey", Base64.getEncoder().encodeToString(fallback));

            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(config, new OkHttpClient());

            assertTrue(provider.keys().isEmpty());
        }
    }

    @Test
    void rejectsMetadataWithUnexpectedIssuerOrInvalidKeySize() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{"
                    + "\"issuer\":\"unexpected\","
                    + "\"algorithm\":\"Ed25519\","
                    + "\"current_public_key\":\"" + Base64.getEncoder().encodeToString(new byte[31]) + "\","
                    + "\"cache_max_age_seconds\":120"
                    + "}"));
            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(
                    config(server.url("/keys.json").toString()), new OkHttpClient());

            assertTrue(provider.keys().isEmpty());
        }
    }

    @Test
    void usesBoundedStaleKeysWhenMetadataScopeRefreshIsInvalid() throws Exception {
        byte[] current = filledKey((byte) 6);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-15T12:00:00Z"));
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(metadata("minekube-connect", current, 1)));
            server.enqueue(new MockResponse().setBody(metadata("unexpected", current, 1)));
            server.enqueue(new MockResponse().setBody(metadata("unexpected", current, 1)));
            ConnectConfig config = config(server.url("/keys.json").toString());
            setField(config.getBedrockIdentity(), "metadataMaxStaleSeconds", 10);
            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(
                    config,
                    new OkHttpClient(),
                    now::get);

            assertKeys(provider.keys(), current);
            now.set(now.get().plusSeconds(2));
            assertKeys(provider.keys(), current);
            now.set(now.get().plusSeconds(10));
            assertTrue(provider.keys().isEmpty());
        }
    }

    @Test
    void rejectsRedirectAndOversizedMetadataBeforeCaching() throws Exception {
        byte[] current = filledKey((byte) 7);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/redirected"));
            server.enqueue(new MockResponse().setBody(metadata("minekube-connect", current, 120) + " ".repeat(65_537)));
            ConnectConfig config = config(server.url("/keys.json").toString());
            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(config, new OkHttpClient());

            assertTrue(provider.keys().isEmpty());
            assertTrue(new BedrockIdentityKeyProvider(config, new OkHttpClient()).keys().isEmpty());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test
    void limitsMetadataCallsToFiveSeconds() throws Exception {
        byte[] current = filledKey((byte) 8);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setBody(metadata("minekube-connect", current, 120))
                    .setHeadersDelay(6, TimeUnit.SECONDS));
            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(
                    config(server.url("/keys.json").toString()), new OkHttpClient());

            assertTimeoutPreemptively(Duration.ofMillis(5_500), () -> assertTrue(provider.keys().isEmpty()));
        }
    }

    @Test
    void sharesOneExpiredMetadataRefreshAcrossConcurrentCallers() throws Exception {
        byte[] current = filledKey((byte) 9);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setBody(metadata("minekube-connect", current, 120))
                    .setHeadersDelay(250, TimeUnit.MILLISECONDS));
            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(
                    config(server.url("/keys.json").toString()), new OkHttpClient());
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                for (int i = 0; i < 8; i++) {
                    executor.submit(provider::keys);
                }
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    void backsOffFailedRefreshesAndFailsClosedAfterHardStaleDeadline() throws Exception {
        byte[] current = filledKey((byte) 10);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-15T12:00:00Z"));
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(metadata("minekube-connect", current, 1)));
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setResponseCode(503));
            ConnectConfig config = config(server.url("/keys.json").toString());
            setField(config.getBedrockIdentity(), "metadataMaxStaleSeconds", 10);
            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(
                    config, new OkHttpClient(), now::get);

            assertKeys(provider.keys(), current);
            now.set(now.get().plusSeconds(10));
            assertKeys(provider.keys(), current);
            assertKeys(provider.keys(), current);
            assertEquals(2, server.getRequestCount());
            now.set(Instant.parse("2026-07-15T12:00:12Z"));
            assertTrue(provider.keys().isEmpty());
            assertTrue(provider.keys().isEmpty());
            assertEquals(2, server.getRequestCount());
            now.set(Instant.parse("2026-07-15T12:00:15Z"));
            assertTrue(provider.keys().isEmpty());
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test
    void usesPostFailureTimeForStaleEligibilityAndRetryBackoff() throws Exception {
        Instant fetchedAt = Instant.parse("2026-07-15T12:00:00Z");
        AtomicReference<Instant> beforeRequest = new AtomicReference<>(fetchedAt);
        AtomicReference<Instant> afterSecondRequest = new AtomicReference<>(fetchedAt.plusSeconds(15));
        byte[] current = filledKey((byte) 11);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(metadata("minekube-connect", current, 1)));
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setResponseCode(503));
            ConnectConfig config = config(server.url("/keys.json").toString());
            setField(config.getBedrockIdentity(), "metadataMaxStaleSeconds", 10);
            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(
                    config,
                    new OkHttpClient(),
                    () -> server.getRequestCount() >= 2
                            ? afterSecondRequest.get()
                            : beforeRequest.get());

            assertKeys(provider.keys(), current);
            beforeRequest.set(fetchedAt.plusSeconds(10));

            assertTrue(provider.keys().isEmpty());
            assertTrue(provider.keys().isEmpty());
            assertEquals(2, server.getRequestCount());

            afterSecondRequest.set(fetchedAt.plusSeconds(20));
            assertTrue(provider.keys().isEmpty());
            assertEquals(3, server.getRequestCount());
        }
    }

    private static ConnectConfig config(String metadataUrl) {
        ConnectConfig config = new ConnectConfig();
        setField(config.getBedrockIdentity(), "metadataUrl", metadataUrl);
        return config;
    }

    private static byte[] filledKey(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return key;
    }

    private static String metadata(String issuer, byte[] current, int cacheSeconds) {
        return "{"
                + "\"issuer\":\"" + issuer + "\","
                + "\"algorithm\":\"Ed25519\","
                + "\"current_public_key\":\"" + Base64.getEncoder().encodeToString(current) + "\","
                + "\"cache_max_age_seconds\":" + cacheSeconds
                + "}";
    }

    private static void assertKeys(List<byte[]> actual, byte[]... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual.get(i));
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
