package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.network.netty.LocalSession;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfileProperty;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BedrockIdentityEnforcerTest {
    @Test
    void enforcerDoesNotOwnACompatibilityClaimsRegistry() {
        assertFalse(java.util.Arrays.stream(BedrockIdentityEnforcer.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(VerifiedBedrockIdentityRegistry.class::equals));
    }

    private static final Gson GSON = new Gson();
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final String VALID_NONCE = "AAAAAAAAAAAAAAAAAAAAAA";

    @Test
    void retainsLegacyHttpClientConstructor() throws Exception {
        Constructor<BedrockIdentityEnforcer> constructor = BedrockIdentityEnforcer.class.getConstructor(
                ConnectConfig.class,
                ConnectLogger.class,
                OkHttpClient.class);

        assertNotNull(constructor.newInstance(
                new ConnectConfig(), mock(ConnectLogger.class), new OkHttpClient()));
    }

    @Test
    void legacyConstructorUsesContextAdmissionRegistry() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(
                keyPair,
                VALID_NONCE,
                "session-1",
                config.getEndpoint(),
                "minekube-connect-test",
                Instant.now());
        VerifiedBedrockIdentityRegistry admissionRegistry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                admissionRegistry, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
        try {
            com.minekube.connect.watch.SessionProposal proposal = coordinator.proposal(
                    session(envelope), reason -> {}, "endpoint-id", "org-id");
            ConnectPlayer player = coordinator.stage(proposal);
            LocalSession.Context context = mock(LocalSession.Context.class);
            when(context.getPlayer()).thenReturn(player);
            when(context.getEndpointId()).thenReturn("endpoint-id");
            when(context.getEndpointOrgId()).thenReturn("org-id");
            when(context.getProtocol()).thenReturn(SessionProtocol.SESSION_PROTOCOL_BEDROCK);
            when(context.getAdmissionToken()).thenReturn(proposal.getAdmissionToken());
            BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                    config, mock(ConnectLogger.class), () -> Instant.now(),
                    new BedrockIdentityKeyProvider(config, new OkHttpClient()), coordinator);

            BedrockIdentityEnforcer.Decision decision = enforcer.verify(context);

            assertTrue(decision.allowed());
            assertNotNull(decision.verifiedClaims());
            assertSame(decision.verifiedClaims(), admissionRegistry.get(player).orElseThrow());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void slowMetadataVerificationDoesNotBlockUnrelatedStageOrRemove() throws Exception {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        try (SlowAdmission slowAdmission = new SlowAdmission(registry)) {
            Future<BedrockIdentityEnforcer.Decision> verification = slowAdmission.start();

            ConnectPlayer unrelated = slowAdmission.completeWhileBlocked(() -> slowAdmission.coordinator.stage(
                    slowAdmission.coordinator.proposal(session("unrelated-envelope").toBuilder()
                            .setId("session-2").build(), reason -> {}, "", "")));
            slowAdmission.completeWhileBlocked(() -> {
                slowAdmission.coordinator.discard(unrelated);
                return null;
            });

            BedrockIdentityEnforcer.Decision decision = slowAdmission.finish(verification);
            assertTrue(decision.allowed());
            assertNotNull(decision.verifiedClaims());
            assertSame(decision.verifiedClaims(), registry.get(slowAdmission.player).orElseThrow());
        }
    }

    @Test
    void slowMetadataVerificationDoesNotBlockExpiryAndCannotPublishAfterIt() throws Exception {
        AtomicReference<Runnable> expiry = new AtomicReference<>();
        ScheduledExecutorService cleanupExecutor = mock(ScheduledExecutorService.class);
        when(cleanupExecutor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    expiry.set(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        try (SlowAdmission slowAdmission = new SlowAdmission(registry, cleanupExecutor)) {
            Future<BedrockIdentityEnforcer.Decision> verification = slowAdmission.start();

            slowAdmission.completeWhileBlocked(() -> {
                expiry.get().run();
                return null;
            });

            BedrockIdentityEnforcer.Decision decision = slowAdmission.finish(verification);
            assertFalse(decision.allowed());
            assertTrue(registry.get(slowAdmission.player).isEmpty());
            System.out.println("SLOW verification + expiry: cleanupCompletedWhileBlocked=true, "
                    + "staleVerificationAllowed=false, staleClaimsPublished=false");
        }
    }

    @Test
    void slowMetadataVerificationDoesNotBlockCloseAndCannotPublishAfterIt() throws Exception {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        try (SlowAdmission slowAdmission = new SlowAdmission(registry)) {
            Future<BedrockIdentityEnforcer.Decision> verification = slowAdmission.start();

            slowAdmission.completeWhileBlocked(() -> {
                slowAdmission.coordinator.close();
                return null;
            });

            BedrockIdentityEnforcer.Decision decision = slowAdmission.finish(verification);
            assertFalse(decision.allowed());
            assertTrue(registry.get(slowAdmission.player).isEmpty());
            System.out.println("SLOW verification + close: closeCompletedWhileBlocked=true, "
                    + "staleVerificationAllowed=false, staleClaimsPublished=false");
        }
    }

    @Test
    void replacementDuringSlowMetadataVerificationCannotPublishStaleClaims() throws Exception {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        try (SlowAdmission slowAdmission = new SlowAdmission(registry)) {
            Future<BedrockIdentityEnforcer.Decision> verification = slowAdmission.start();

            ConnectPlayer replacement = slowAdmission.completeWhileBlocked(
                    () -> slowAdmission.coordinator.stage(slowAdmission.coordinator.proposal(
                            session(slowAdmission.envelope), reason -> {}, "", "")));

            BedrockIdentityEnforcer.Decision decision = slowAdmission.finish(verification);
            assertFalse(decision.allowed());
            assertTrue(registry.get(slowAdmission.player).isEmpty());
            assertFalse(replacement.getGameProfile().toString().contains(VALID_NONCE));
            System.out.println("SLOW verification + replacement: replacementCompletedWhileBlocked=true, "
                    + "staleVerificationAllowed=false, staleClaimsPublished=false, "
                    + "replacementPrivateEnvelope=false");
        }
    }

    @Test
    void disabledModeAllowsMissingIdentityWithoutLogging() {
        ConnectLogger logger = mock(ConnectLogger.class);
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("disabled", "", "trusted_bedrock_xuid"),
                logger,
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(player("session-1", profileWithoutEnvelope()));

        assertTrue(decision.allowed());
        verify(logger, never()).warn(any(), any());
    }

    @Test
    void requireModeAllowsValidEnvelopeAndReturnsClaims() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "endpoint-id",
                "org-id");

        assertTrue(decision.allowed());
        assertNotNull(decision.verifiedClaims());
        assertEquals("2533274790395904", decision.verifiedClaims().getBedrockXuid());
    }

    @Test
    void malformedConfigurationRejectsOtherwiseValidIdentity() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig normalizedMode = config(
                "REQUIRE",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String normalizedModeEnvelope = signedEnvelope(
                keyPair, VALID_NONCE, "session-1", normalizedMode.getEndpoint());
        BedrockIdentityEnforcer normalizedModeEnforcer = new BedrockIdentityEnforcer(
                normalizedMode, mock(ConnectLogger.class), () -> NOW);

        assertFalse(normalizedModeEnforcer.verify(
                player("session-1", profileWithEnvelope(normalizedModeEnvelope)),
                "endpoint-id",
                "org-id").allowed());

        ConnectConfig blankIssuer = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        setField(blankIssuer.getBedrockIdentity(), "expectedIssuer", "   ");
        String blankIssuerEnvelope = signedEnvelope(
                keyPair, VALID_NONCE, "session-2", blankIssuer.getEndpoint(), "   ");
        BedrockIdentityEnforcer blankIssuerEnforcer = new BedrockIdentityEnforcer(
                blankIssuer, mock(ConnectLogger.class), () -> NOW);

        assertFalse(blankIssuerEnforcer.verify(
                player("session-2", profileWithEnvelope(blankIssuerEnvelope)),
                "endpoint-id",
                "org-id").allowed());
    }

    @Test
    void admissionPublishesClaimsWithoutExposingOrRetainingEnvelope() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                registry, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
        com.minekube.connect.watch.SessionProposal proposal = coordinator.proposal(
                session(envelope), reason -> {}, "endpoint-id", "org-id");
        ConnectPlayer player = coordinator.stage(proposal);
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config,
                mock(ConnectLogger.class),
                () -> NOW,
                coordinator);

        BedrockIdentityEnforcer.Decision decision = coordinator.verify(player, proposal.getAdmissionToken(), enforcer,
                "endpoint-id", "org-id", SessionProtocol.SESSION_PROTOCOL_BEDROCK);

        assertTrue(decision.allowed());
        assertNotNull(decision.verifiedClaims());
        assertTrue(registry.get(player).isPresent());
        assertFalse(player.getGameProfile().toString().contains(VALID_NONCE));
    }

    @Test
    void disabledAdmissionDropsEnvelopeWithoutPublishingClaims() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
        com.minekube.connect.watch.SessionProposal proposal = coordinator.proposal(session("invalid-envelope-nonce-a"), reason -> {}, "", "");
        ConnectPlayer player = coordinator.stage(proposal);
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("disabled", "", "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW,
                coordinator);

        BedrockIdentityEnforcer.Decision decision = coordinator.verify(player, proposal.getAdmissionToken(), enforcer,
                "", "", SessionProtocol.SESSION_PROTOCOL_BEDROCK);

        assertTrue(decision.allowed());
        assertEquals(null, decision.verifiedClaims());
        assertTrue(registry.get(player).isEmpty());
        assertFalse(player.getGameProfile().toString().contains("nonce-a"));
    }

    @Test
    void warnFailedAdmissionDropsEnvelopeWithoutPublishingClaims() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
        com.minekube.connect.watch.SessionProposal proposal = coordinator.proposal(session("invalid-envelope-nonce-a"), reason -> {}, "", "");
        ConnectPlayer player = coordinator.stage(proposal);
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("warn", base64(keyPair.getPublic().getEncoded()), "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW,
                coordinator);

        BedrockIdentityEnforcer.Decision decision = coordinator.verify(player, proposal.getAdmissionToken(), enforcer,
                "", "", SessionProtocol.SESSION_PROTOCOL_BEDROCK);

        assertTrue(decision.allowed());
        assertEquals(null, decision.verifiedClaims());
        assertTrue(registry.get(player).isEmpty());
        assertFalse(player.getGameProfile().toString().contains("nonce-a"));
    }

    @Test
    void rejectedAdmissionDropsEnvelopeWithoutPublishingClaims() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
        com.minekube.connect.watch.SessionProposal proposal = coordinator.proposal(session("invalid-envelope-nonce-a"), reason -> {}, "", "");
        ConnectPlayer player = coordinator.stage(proposal);
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("require", base64(keyPair.getPublic().getEncoded()), "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW,
                coordinator);

        BedrockIdentityEnforcer.Decision decision = coordinator.verify(player, proposal.getAdmissionToken(), enforcer,
                "", "", SessionProtocol.SESSION_PROTOCOL_BEDROCK);

        assertFalse(decision.allowed());
        assertTrue(registry.get(player).isEmpty());
        assertFalse(player.getGameProfile().toString().contains("nonce-a"));
    }

    @Test
    void requireModeAllowsEnvelopeSignedByAdditionalPublicKey() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                "",
                "trusted_bedrock_xuid");
        setField(config.getBedrockIdentity(), "publicKeys", Collections.singletonList(base64(keyPair.getPublic().getEncoded())));
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "endpoint-id",
                "org-id");

        assertTrue(decision.allowed());
        assertNotNull(decision.verifiedClaims());
    }

    @Test
    void requireModeRejectsEndpointScopeMismatchWhenLocalScopeIsPresent() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "other-endpoint-id",
                "org-id");

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("Bedrock identity verification failed"));
    }

    @Test
    void requireModeRejectsOrgScopeMismatchWhenLocalScopeIsPresent() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "endpoint-id",
                "other-org-id");

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("Bedrock identity verification failed"));
    }

    @Test
    void requireModeRejectsIdentityWhenEitherAuthenticatedScopeValueIsMissing() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);
        ConnectPlayer player = player("session-1", profileWithEnvelope(envelope));

        assertFalse(enforcer.verify(player, "", "org-id").allowed());
        assertFalse(enforcer.verify(player, "endpoint-id", " ").allowed());
    }

    @Test
    void requireModeRejectsMissingIdentity() {
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("require", base64(new byte[32]), "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(player("session-1", profileWithoutEnvelope()));

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("Bedrock identity verification failed"));
    }

    @Test
    void requireModeLeavesMarkedJavaWithoutReservedIdentityUnchanged() {
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("require", base64(new byte[32]), "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithoutEnvelope()),
                "",
                "",
                SessionProtocol.SESSION_PROTOCOL_JAVA);

        assertTrue(decision.allowed());
        assertEquals(null, decision.verifiedClaims());
    }

    @Test
    void requireModeRejectsReservedIdentityOnPassthroughJavaAdmission() {
        ConnectPlayer player = BedrockAdmissionCoordinator.playerFor(
                session("reserved-envelope-nonce-a", true));
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("require", base64(new byte[32]), "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player,
                "",
                "",
                SessionProtocol.SESSION_PROTOCOL_JAVA);

        assertFalse(decision.allowed());
    }

    @Test
    void requireModeRejectsMarkedBedrockWithoutReservedIdentity() {
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("require", base64(new byte[32]), "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithoutEnvelope()),
                "",
                "",
                SessionProtocol.SESSION_PROTOCOL_BEDROCK);

        assertFalse(decision.allowed());
    }

    @Test
    void requireModeAllowsValidLegacyUnspecifiedEnvelope() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "endpoint-id",
                "org-id",
                SessionProtocol.SESSION_PROTOCOL_UNSPECIFIED);

        assertTrue(decision.allowed());
        assertNotNull(decision.verifiedClaims());
    }

    @Test
    void warnModeNeverPublishesClaimsForUnrecognizedProtocol() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "warn",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "",
                "",
                SessionProtocol.UNRECOGNIZED);

        assertTrue(decision.allowed());
        assertEquals(null, decision.verifiedClaims());
    }

    @Test
    void warnModeLogsAndAllowsMissingIdentity() {
        ConnectLogger logger = mock(ConnectLogger.class);
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("warn", base64(new byte[32]), "trusted_bedrock_xuid"),
                logger,
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(player("session-1", profileWithoutEnvelope()));

        assertTrue(decision.allowed());
        verify(logger).warn(any(), any(), any(), any());
    }

    @Test
    void warnModeAllowsInvalidPublicKeyWithoutLoggingKeyMaterial() {
        ConnectLogger logger = mock(ConnectLogger.class);
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("warn", "not-base64-key-material", "trusted_bedrock_xuid"),
                logger,
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(player("session-1", profileWithoutEnvelope()));

        assertTrue(decision.allowed());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logger).warn(message.capture(), any(), any(), any());
        assertFalse(message.getValue().contains("not-base64-key-material"));
    }

    @Test
    void requireModeRejectsInvalidPublicKey() {
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(
                config("require", "not-base64-key-material", "trusted_bedrock_xuid"),
                mock(ConnectLogger.class),
                () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(player("session-1", profileWithoutEnvelope()));

        assertFalse(decision.allowed());
        assertFalse(decision.message().contains("not-base64-key-material"));
    }

    @Test
    void requireModeRejectsReplay() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                base64(keyPair.getPublic().getEncoded()),
                "trusted_bedrock_xuid");
        String envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);
        ConnectPlayer player = player("session-1", profileWithEnvelope(envelope));

        assertTrue(enforcer.verify(player, "endpoint-id", "org-id").allowed());
        BedrockIdentityEnforcer.Decision replay = enforcer.verify(player, "endpoint-id", "org-id");

        assertFalse(replay.allowed());
        assertTrue(replay.message().contains("Bedrock identity verification failed"));
    }

    private static ConnectPlayer player(String sessionId, GameProfile profile) {
        return new ConnectPlayerImpl(sessionId, profile, new Auth(false), "");
    }

    private static GameProfile profileWithoutEnvelope() {
        return new GameProfile(
                "BedrockSteve",
                UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3"),
                Collections.singletonList(new GameProfile.Property("textures", "skin", "")));
    }

    private static GameProfile profileWithEnvelope(String envelope) {
        return new GameProfile(
                "BedrockSteve",
                UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3"),
                Arrays.asList(
                        new GameProfile.Property("textures", "skin", ""),
                        new GameProfile.Property(BedrockIdentityVerifier.PROPERTY_NAME, envelope, "")));
    }

    private static ConnectConfig config(String enforcement, String publicKey, String expectedPolicy) {
        ConnectConfig config = new ConnectConfig();
        ConnectConfig.BedrockIdentityConfig bedrockIdentity = config.getBedrockIdentity();
        setField(bedrockIdentity, "enforcement", enforcement);
        setField(bedrockIdentity, "publicKey", publicKey);
        setField(bedrockIdentity, "expectedPolicy", expectedPolicy);
        setField(bedrockIdentity, "expectedIssuer", "minekube-connect-test");
        return config;
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

    private static KeyPair ed25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static String metadata(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] rawPublicKey = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        return "{"
                + "\"issuer\":\"minekube-connect-test\","
                + "\"algorithm\":\"Ed25519\","
                + "\"current_public_key\":\"" + base64(rawPublicKey) + "\","
                + "\"cache_max_age_seconds\":120"
                + "}";
    }

    private static String signedEnvelope(
            KeyPair keyPair,
            String nonce,
            String sessionId,
            String endpointName) throws Exception {
        return signedEnvelope(keyPair, nonce, sessionId, endpointName, "minekube-connect-test");
    }

    private static String signedEnvelope(
            KeyPair keyPair,
            String nonce,
            String sessionId,
            String endpointName,
            String issuer) throws Exception {
        return signedEnvelope(keyPair, nonce, sessionId, endpointName, issuer, NOW);
    }

    private static String signedEnvelope(
            KeyPair keyPair,
            String nonce,
            String sessionId,
            String endpointName,
            String issuer,
            Instant issuedAt) throws Exception {
        Envelope envelope = new Envelope();
        envelope.version = 1;
        envelope.issuer = issuer;
        envelope.endpoint = new Endpoint();
        envelope.endpoint.id = "endpoint-id";
        envelope.endpoint.name = endpointName;
        envelope.endpoint.org_id = "org-id";
        envelope.session = new Session();
        envelope.session.id = sessionId;
        envelope.session.protocol = "bedrock";
        envelope.session.issued_at_unix_ms = issuedAt.toEpochMilli();
        envelope.session.expires_at_unix_ms = issuedAt.plusSeconds(300).toEpochMilli();
        envelope.session.nonce = nonce;
        envelope.policy = new Policy();
        envelope.policy.bedrock_auth_mode = "trusted_bedrock_xuid";
        envelope.principal = new Principal();
        envelope.principal.type = "bedrock_xuid";
        envelope.principal.bedrock_xuid = "2533274790395904";
        envelope.principal.bedrock_username = "BedrockSteve";
        envelope.principal.bedrock_derived_uuid = "f912bf90-8349-565f-9dc0-9891923c0cc3";

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8));
        envelope.signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        return GSON.toJson(envelope);
    }

    private static minekube.connect.v1alpha1.WatchServiceOuterClass.Session session(String envelope) {
        return session(envelope, false);
    }

    private static minekube.connect.v1alpha1.WatchServiceOuterClass.Session session(
            String envelope,
            boolean passthrough) {
        return minekube.connect.v1alpha1.WatchServiceOuterClass.Session.newBuilder()
                .setId("session-1")
                .setAuth(Authentication.newBuilder().setPassthrough(passthrough))
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile.newBuilder()
                                .setId("f912bf90-8349-565f-9dc0-9891923c0cc3")
                                .setName("BedrockSteve")
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("textures")
                                        .setValue("skin"))
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName(BedrockIdentityVerifier.PROPERTY_NAME)
                                        .setValue(envelope))
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity_scope")
                                        .setValue("private-endpoint-id"))))
                .build();
    }

    private static final class Envelope {
        int version;
        String issuer;
        Endpoint endpoint;
        Session session;
        Policy policy;
        Principal principal;
        String signature;
    }

    private static final class Endpoint {
        String id;
        String name;
        String org_id;
    }

    private static final class Session {
        String id;
        String protocol;
        long issued_at_unix_ms;
        long expires_at_unix_ms;
        String nonce;
    }

    private static final class Policy {
        String bedrock_auth_mode;
    }

    private static final class Principal {
        String type;
        String bedrock_xuid;
        String bedrock_username;
        String bedrock_derived_uuid;
    }

    private static final class SlowAdmission implements AutoCloseable {
        private final VerifiedBedrockIdentityRegistry registry;
        private final BedrockAdmissionCoordinator coordinator;
        private final CountDownLatch fetchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFetch = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final ConnectPlayer player;
        private final String envelope;
        private final BedrockIdentityEnforcer enforcer;
        private final LocalSession.Context context;

        private SlowAdmission(VerifiedBedrockIdentityRegistry registry) throws Exception {
            this(registry, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
        }

        private SlowAdmission(
                VerifiedBedrockIdentityRegistry registry,
                ScheduledExecutorService cleanupExecutor) throws Exception {
            this.registry = registry;
            KeyPair keyPair = ed25519KeyPair();
            ConnectConfig config = config("require", "", "trusted_bedrock_xuid");
            setField(config.getBedrockIdentity(), "metadataUrl", "https://metadata.example/keys");
            envelope = signedEnvelope(keyPair, VALID_NONCE, "session-1", config.getEndpoint());
            coordinator = new BedrockAdmissionCoordinator(registry, cleanupExecutor);
            com.minekube.connect.watch.SessionProposal proposal = coordinator.proposal(
                    session(envelope), reason -> {}, "endpoint-id", "org-id");
            player = coordinator.stage(proposal);
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .addInterceptor(chain -> {
                        fetchStarted.countDown();
                        try {
                            if (!releaseFetch.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("metadata test release timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("metadata test interrupted", e);
                        }
                        return new Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(ResponseBody.create(
                                        MediaType.get("application/json"),
                                        metadata(keyPair)))
                                .build();
                    })
                    .build();
            BedrockIdentityKeyProvider keyProvider = new BedrockIdentityKeyProvider(config, httpClient, () -> NOW);
            enforcer = new BedrockIdentityEnforcer(
                    config,
                    mock(ConnectLogger.class),
                    () -> NOW,
                    keyProvider,
                    coordinator);
            context = mock(LocalSession.Context.class);
            when(context.getPlayer()).thenReturn(player);
            when(context.getEndpointId()).thenReturn("endpoint-id");
            when(context.getEndpointOrgId()).thenReturn("org-id");
            when(context.getProtocol()).thenReturn(SessionProtocol.SESSION_PROTOCOL_BEDROCK);
            when(context.getAdmissionToken()).thenReturn(proposal.getAdmissionToken());
        }

        private Future<BedrockIdentityEnforcer.Decision> start() throws Exception {
            Future<BedrockIdentityEnforcer.Decision> verification = executor.submit(() -> enforcer.verify(context));
            assertTrue(fetchStarted.await(2, TimeUnit.SECONDS));
            return verification;
        }

        private <T> T completeWhileBlocked(Callable<T> operation) throws Exception {
            return executor.submit(operation).get(500, TimeUnit.MILLISECONDS);
        }

        private BedrockIdentityEnforcer.Decision finish(
                Future<BedrockIdentityEnforcer.Decision> verification) throws Exception {
            releaseFetch.countDown();
            return verification.get(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            releaseFetch.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
            coordinator.close();
        }
    }
}
