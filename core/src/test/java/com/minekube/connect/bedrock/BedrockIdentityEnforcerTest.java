package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.gson.Gson;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BedrockIdentityEnforcerTest {
    private static final Gson GSON = new Gson();
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");

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
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(player("session-1", profileWithEnvelope(envelope)));

        assertTrue(decision.allowed());
        assertNotNull(decision.verifiedClaims());
        assertEquals("2533274790395904", decision.verifiedClaims().getBedrockXuid());
    }

    @Test
    void requireModeAllowsEnvelopeSignedByAdditionalPublicKey() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        ConnectConfig config = config(
                "require",
                "",
                "trusted_bedrock_xuid");
        setField(config.getBedrockIdentity(), "publicKeys", Collections.singletonList(base64(keyPair.getPublic().getEncoded())));
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(player("session-1", profileWithEnvelope(envelope)));

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
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", config.getEndpoint());
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
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "endpoint-id",
                "other-org-id");

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("Bedrock identity verification failed"));
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
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);

        BedrockIdentityEnforcer.Decision decision = enforcer.verify(
                player("session-1", profileWithEnvelope(envelope)),
                "",
                "",
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
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", config.getEndpoint());
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
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", config.getEndpoint());
        BedrockIdentityEnforcer enforcer = new BedrockIdentityEnforcer(config, mock(ConnectLogger.class), () -> NOW);
        ConnectPlayer player = player("session-1", profileWithEnvelope(envelope));

        assertTrue(enforcer.verify(player).allowed());
        BedrockIdentityEnforcer.Decision replay = enforcer.verify(player);

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

    private static String signedEnvelope(
            KeyPair keyPair,
            String nonce,
            String sessionId,
            String endpointName) throws Exception {
        Envelope envelope = new Envelope();
        envelope.version = 1;
        envelope.issuer = "minekube-connect-test";
        envelope.endpoint = new Endpoint();
        envelope.endpoint.id = "endpoint-id";
        envelope.endpoint.name = endpointName;
        envelope.endpoint.org_id = "org-id";
        envelope.session = new Session();
        envelope.session.id = sessionId;
        envelope.session.protocol = "bedrock";
        envelope.session.issued_at_unix_ms = NOW.toEpochMilli();
        envelope.session.expires_at_unix_ms = NOW.plusSeconds(300).toEpochMilli();
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
}
