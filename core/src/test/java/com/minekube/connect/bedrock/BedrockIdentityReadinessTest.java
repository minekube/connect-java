package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.bedrock.BedrockIdentityReadiness.Transport;
import com.minekube.connect.config.ConnectConfig;
import java.lang.reflect.Field;
import java.util.Base64;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class BedrockIdentityReadinessTest {
    @Test
    void requiresNonDisabledEnforcementAndValidatedStaticKey() {
        ConnectConfig disabled = config("disabled");
        setField(disabled.getBedrockIdentity(), "publicKey", encodedKey((byte) 1));
        assertFalse(new BedrockIdentityReadiness(disabled,
                new BedrockIdentityKeyProvider(disabled, new OkHttpClient())).isReady());

        ConnectConfig malformed = config("require");
        setField(malformed.getBedrockIdentity(), "publicKey", "not-base64");
        assertFalse(new BedrockIdentityReadiness(malformed,
                new BedrockIdentityKeyProvider(malformed, new OkHttpClient())).isReady());

        ConnectConfig unparseable = config("require");
        setField(unparseable.getBedrockIdentity(), "publicKey",
                Base64.getEncoder().encodeToString(new byte[44]));
        assertFalse(new BedrockIdentityReadiness(unparseable,
                new BedrockIdentityKeyProvider(unparseable, new OkHttpClient())).isReady());

        ConnectConfig valid = config("require");
        setField(valid.getBedrockIdentity(), "publicKey", encodedKey((byte) 2));
        assertTrue(new BedrockIdentityReadiness(valid,
                new BedrockIdentityKeyProvider(valid, new OkHttpClient())).isReady());

        ConnectConfig unknownMode = config("bogus");
        setField(unknownMode.getBedrockIdentity(), "publicKey", encodedKey((byte) 4));
        assertFalse(new BedrockIdentityReadiness(unknownMode,
                new BedrockIdentityKeyProvider(unknownMode, new OkHttpClient())).isReady());
    }

    @Test
    void fansReadinessTransitionsToWatchAndLibp2pIndependently() {
        ConnectConfig config = config("require");
        setField(config.getBedrockIdentity(), "publicKey", encodedKey((byte) 4));
        BedrockIdentityReadiness readiness = new BedrockIdentityReadiness(
                config,
                new BedrockIdentityKeyProvider(config, new OkHttpClient()));

        assertTrue(readiness.observe(Transport.WATCH));
        assertTrue(readiness.observe(Transport.LIBP2P));
        setField(config.getBedrockIdentity(), "enforcement", "disabled");

        assertTrue(readiness.refresh(Transport.WATCH));
        assertTrue(readiness.refresh(Transport.LIBP2P));
        assertFalse(readiness.refresh(Transport.WATCH));
        assertFalse(readiness.refresh(Transport.LIBP2P));

        setField(config.getBedrockIdentity(), "enforcement", "require");
        assertTrue(readiness.refresh(Transport.LIBP2P));
        assertTrue(readiness.refresh(Transport.WATCH));
    }

    @Test
    void metadataRequiresInitialSuccessfulValidationBeforeAdvertising() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            ConnectConfig config = config("require");
            setField(config.getBedrockIdentity(), "metadataUrl", server.url("/keys").toString());
            setField(config.getBedrockIdentity(), "publicKey", encodedKey((byte) 3));

            assertFalse(new BedrockIdentityReadiness(config,
                    new BedrockIdentityKeyProvider(config, new OkHttpClient())).isReady());
        }
    }

    private static ConnectConfig config(String enforcement) {
        ConnectConfig config = new ConnectConfig();
        setField(config.getBedrockIdentity(), "enforcement", enforcement);
        return config;
    }

    private static String encodedKey(byte value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
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
