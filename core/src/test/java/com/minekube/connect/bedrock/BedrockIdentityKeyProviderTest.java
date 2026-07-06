package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.minekube.connect.config.ConnectConfig;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
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
    void fallsBackToStaticKeysWhenMetadataFetchFails() throws Exception {
        byte[] fallback = filledKey((byte) 5);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            ConnectConfig config = config(server.url("/keys.json").toString());
            setField(config.getBedrockIdentity(), "publicKey", Base64.getEncoder().encodeToString(fallback));

            BedrockIdentityKeyProvider provider = new BedrockIdentityKeyProvider(config, new OkHttpClient());

            assertKeys(provider.keys(), fallback);
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
