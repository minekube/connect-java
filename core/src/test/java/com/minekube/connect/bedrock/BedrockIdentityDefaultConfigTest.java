package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.config.ConfigLoader;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.config.ProxyConnectConfig;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BedrockIdentityDefaultConfigTest {
    private static final String METADATA_URL =
            "https://watch-connect.minekube.net/.well-known/minekube-connect/bedrock-identity-keys.json";
    private static final String FIXTURE_PUBLIC_KEY = "EvLQyZOnKxAUFaKqU2AhyOLXzPL6cHBNZjqwiLPxQy4=";
    private static final Instant FIXTURE_TIME = Instant.ofEpochMilli(1_783_260_000_000L);

    @TempDir Path tempDir;

    @Test
    void serverAndProxyShipTrustedWarnDefaults() throws Exception {
        ConnectConfig server = loadUntouched(ConnectConfig.class, tempDir.resolve("server"));
        ProxyConnectConfig proxy = loadUntouched(ProxyConnectConfig.class, tempDir.resolve("proxy"));

        assertTrustedWarnDefaults(server);
        assertTrustedWarnDefaults(proxy);
    }

    @Test
    void untouchedServerConfigAdvertisesAndAdmitsValidUnlinkedBedrock() throws Exception {
        ConnectConfig config = loadUntouched(ConnectConfig.class, tempDir.resolve("bedrock"));
        AtomicInteger metadataRequests = new AtomicInteger();
        OkHttpClient metadataClient = metadataClient(metadataRequests);
        BedrockIdentityKeyProvider keyProvider =
                new BedrockIdentityKeyProvider(config, metadataClient, () -> FIXTURE_TIME);
        BedrockIdentityReadiness readiness = new BedrockIdentityReadiness(config, keyProvider);

        assertEquals(
                List.of("session", "status", BedrockIdentityReadiness.CAPABILITY),
                readiness.capabilities(
                        List.of("session", "status"),
                        BedrockIdentityReadiness.Transport.WATCH));

        ConnectPlayer player = new ConnectPlayerImpl(
                "session-id",
                new GameProfile(
                        "BedrockFox",
                        UUID.fromString("cafc7598-0ef3-527f-8f28-60af2d9ca6bc"),
                        List.of(new GameProfile.Property(
                                BedrockIdentityVerifier.PROPERTY_NAME,
                                resource("/bedrock-identity-v1/v1-bedrock-xuid-valid.json"),
                                ""))),
                new Auth(false),
                "");
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config,
                mock(ConnectLogger.class),
                () -> FIXTURE_TIME,
                keyProvider);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player,
                "endpoint-id",
                "org-id",
                SessionProtocol.SESSION_PROTOCOL_BEDROCK);

        assertTrue(decision.allowed());
        assertNotNull(decision.verifiedClaims());
        assertEquals("bedrock_xuid", decision.verifiedClaims().getPrincipalType());
        assertNull(decision.verifiedClaims().getLinkedJavaUuid());
        assertEquals(1, metadataRequests.get());
    }

    @Test
    void untouchedDefaultsDoNotRejectOrFetchKeysForJavaOnlySessions() throws Exception {
        ConnectConfig config = loadUntouched(ConnectConfig.class, tempDir.resolve("java"));
        AtomicInteger metadataRequests = new AtomicInteger();
        ConnectLogger logger = mock(ConnectLogger.class);
        BedrockIdentityKeyProvider keyProvider =
                new BedrockIdentityKeyProvider(config, metadataClient(metadataRequests), () -> FIXTURE_TIME);
        BedrockIdentityEnforcer enforcer =
                new BedrockIdentityEnforcer(config, logger, () -> FIXTURE_TIME, keyProvider);
        ConnectPlayer javaPlayer = new ConnectPlayerImpl(
                "java-session",
                new GameProfile(
                        "JavaPlayer",
                        UUID.fromString("c66dfcbc-4bd2-4a29-8c76-eadf80faa08a"),
                        Collections.emptyList()),
                new Auth(false),
                "");

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                javaPlayer,
                "",
                "",
                SessionProtocol.SESSION_PROTOCOL_JAVA);

        assertTrue(decision.allowed());
        assertNull(decision.verifiedClaims());
        assertEquals(0, metadataRequests.get());
        verifyNoInteractions(logger);
    }

    private <T extends ConnectConfig> T loadUntouched(Class<T> type, Path directory) throws IOException {
        Files.createDirectories(directory);
        return new ConfigLoader(
                directory,
                type,
                new ConfigLoader.EndpointNameGenerator(endpointNameClient()),
                mock(ConnectLogger.class)).load();
    }

    private static OkHttpClient endpointNameClient() {
        return responseClient("text/plain", "fixture-endpoint", null);
    }

    private static OkHttpClient metadataClient(AtomicInteger requests) {
        String metadata = "{"
                + "\"issuer\":\"minekube-connect\","
                + "\"algorithm\":\"Ed25519\","
                + "\"current_public_key\":\"" + FIXTURE_PUBLIC_KEY + "\","
                + "\"previous_public_keys\":[],"
                + "\"cache_max_age_seconds\":300"
                + "}";
        return responseClient("application/json", metadata, requests);
    }

    private static OkHttpClient responseClient(
            String contentType,
            String body,
            AtomicInteger requests) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    if (requests != null) {
                        assertEquals(METADATA_URL, chain.request().url().toString());
                        requests.incrementAndGet();
                    }
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(MediaType.get(contentType), body))
                            .build();
                })
                .build();
    }

    private static void assertTrustedWarnDefaults(ConnectConfig config) {
        assertEquals("warn", config.getBedrockIdentity().getEnforcement());
        assertEquals(METADATA_URL, config.getBedrockIdentity().getMetadataUrl());
        assertEquals("minekube-connect", config.getBedrockIdentity().getExpectedIssuer());
        assertEquals("trusted_bedrock_xuid", config.getBedrockIdentity().getExpectedPolicy());
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = BedrockIdentityDefaultConfigTest.class.getResourceAsStream(path)) {
            return new String(
                    Objects.requireNonNull(input, "missing test resource " + path).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }
}
