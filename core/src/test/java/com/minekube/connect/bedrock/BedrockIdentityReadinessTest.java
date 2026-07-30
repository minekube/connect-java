package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.bedrock.BedrockIdentityReadiness.Transport;
import com.minekube.connect.config.ConnectConfig;
import java.lang.reflect.Field;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
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
    void rejectsNormalizedModeAndBlankIssuerFromReadiness() {
        ConnectConfig normalizedMode = config("REQUIRE");
        setField(normalizedMode.getBedrockIdentity(), "publicKey", encodedKey((byte) 5));
        assertFalse(new BedrockIdentityReadiness(normalizedMode,
                new BedrockIdentityKeyProvider(normalizedMode, new OkHttpClient())).isReady());

        ConnectConfig blankIssuer = config("require");
        setField(blankIssuer.getBedrockIdentity(), "expectedIssuer", "   ");
        setField(blankIssuer.getBedrockIdentity(), "publicKey", encodedKey((byte) 6));
        assertFalse(new BedrockIdentityReadiness(blankIssuer,
                new BedrockIdentityKeyProvider(blankIssuer, new OkHttpClient())).isReady());
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
    void metadataDoesNotNeedInitialFetchBeforeAdvertising() throws Exception {
        ConnectConfig config = config("require");
        setField(config.getBedrockIdentity(), "metadataUrl", "https://metadata.example/keys");
        AtomicInteger metadataRequests = new AtomicInteger();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    metadataRequests.incrementAndGet();
                    throw new AssertionError("readiness fetched metadata");
                })
                .build();

        BedrockIdentityReadiness readiness = new BedrockIdentityReadiness(
                config, new BedrockIdentityKeyProvider(config, httpClient));

        assertTrue(readiness.isReady());
        assertEquals(0, metadataRequests.get());
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
