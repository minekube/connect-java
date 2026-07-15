package com.minekube.connect.api.player.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.minekube.connect.api.player.GameProfile;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BedrockIdentityVerifierTest {
    private static final Gson GSON = new Gson();
    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");

    @Test
    void verifiesEndpointScopedBedrockXuidEnvelopeFromGameProfileProperty() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        GameProfile profile = profileWithEnvelope(envelope);

        BedrockIdentityVerifier verifier = BedrockIdentityVerifier.builder()
                .publicKey(rawEd25519PublicKey(keyPair))
                .now(NOW)
                .endpointId("endpoint-id")
                .endpointName("endpoint")
                .orgId("org-id")
                .sessionId("session-1")
                .protocol("bedrock")
                .replayCache(new BedrockIdentityReplayCache())
                .build();

        BedrockIdentityClaims claims = verifier.verify(profile);

        assertEquals("bedrock_xuid", claims.getPrincipalType());
        assertEquals("2533274790395904", claims.getBedrockXuid());
        assertEquals("BedrockSteve", claims.getBedrockUsername());
        assertEquals("trusted_bedrock_xuid", claims.getBedrockAuthPolicy());
        assertEquals("endpoint-id", claims.getEndpointId());
    }

    @Test
    void rejectsTamperedEnvelopeSignature() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id")
                .replace("BedrockSteve", "SpoofedSteve");
        BedrockIdentityVerifier verifier = verifier(keyPair, "session-1");

        BedrockIdentityVerificationException error = assertThrows(
                BedrockIdentityVerificationException.class,
                () -> verifier.verify(profileWithEnvelope(envelope)));

        assertTrue(error.getMessage().contains("signature"));
    }

    @Test
    void rejectsScopeMismatch() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        BedrockIdentityVerifier verifier = BedrockIdentityVerifier.builder()
                .publicKey(keyPair.getPublic().getEncoded())
                .now(NOW)
                .endpointId("other-endpoint-id")
                .endpointName("endpoint")
                .orgId("org-id")
                .sessionId("session-1")
                .protocol("bedrock")
                .build();

        BedrockIdentityVerificationException error = assertThrows(
                BedrockIdentityVerificationException.class,
                () -> verifier.verify(profileWithEnvelope(envelope)));

        assertTrue(error.getMessage().contains("scope"));
    }

    @Test
    void verifiesWhenEndpointIdAndOrgIdAreNotLocallyConfigured() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        BedrockIdentityVerifier verifier = BedrockIdentityVerifier.builder()
                .publicKey(keyPair.getPublic().getEncoded())
                .now(NOW)
                .endpointName("endpoint")
                .sessionId("session-1")
                .protocol("bedrock")
                .bedrockAuthPolicy("trusted_bedrock_xuid")
                .build();

        BedrockIdentityClaims claims = verifier.verify(profileWithEnvelope(envelope));

        assertEquals("endpoint-id", claims.getEndpointId());
        assertEquals("org-id", claims.getOrgId());
    }

    @Test
    void rejectsPolicyMismatchWhenExpectedPolicyIsConfigured() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        BedrockIdentityVerifier verifier = BedrockIdentityVerifier.builder()
                .publicKey(keyPair.getPublic().getEncoded())
                .now(NOW)
                .endpointId("endpoint-id")
                .endpointName("endpoint")
                .orgId("org-id")
                .sessionId("session-1")
                .protocol("bedrock")
                .bedrockAuthPolicy("linked_java_only")
                .build();

        BedrockIdentityVerificationException error = assertThrows(
                BedrockIdentityVerificationException.class,
                () -> verifier.verify(profileWithEnvelope(envelope)));

        assertTrue(error.getMessage().contains("policy"));
    }

    @Test
    void rejectsReplayWhenCacheSeesSameEndpointSessionNonceTwice() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        BedrockIdentityReplayCache replayCache = new BedrockIdentityReplayCache();
        BedrockIdentityVerifier verifier = BedrockIdentityVerifier.builder()
                .publicKey(keyPair.getPublic().getEncoded())
                .now(NOW)
                .endpointId("endpoint-id")
                .endpointName("endpoint")
                .orgId("org-id")
                .sessionId("session-1")
                .protocol("bedrock")
                .replayCache(replayCache)
                .build();

        verifier.verify(profileWithEnvelope(envelope));
        BedrockIdentityVerificationException error = assertThrows(
                BedrockIdentityVerificationException.class,
                () -> verifier.verify(profileWithEnvelope(envelope)));

        assertTrue(error.getMessage().contains("replayed"));
    }

    @Test
    void rejectsProfileWithoutBedrockIdentityProperty() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        BedrockIdentityVerifier verifier = verifier(keyPair, "session-1");
        GameProfile profile = new GameProfile(
                "BedrockSteve",
                UUID.randomUUID(),
                Collections.singletonList(new GameProfile.Property("textures", "skin", "")));

        BedrockIdentityVerificationException error = assertThrows(
                BedrockIdentityVerificationException.class,
                () -> verifier.verify(profile));

        assertTrue(error.getMessage().contains(BedrockIdentityVerifier.PROPERTY_NAME));
    }

    @Test
    void rejectsBedrockProfileUuidMismatch() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        GameProfile profile = profileWithEnvelope(
                "BedrockSteve",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                envelope);

        BedrockIdentityVerificationException error = assertThrows(
                BedrockIdentityVerificationException.class,
                () -> verifier(keyPair, "session-1").verify(profile));

        assertTrue(error.getMessage().contains("profile"));
    }

    @Test
    void rejectsBedrockProfileNameMismatch() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        GameProfile profile = profileWithEnvelope(
                "SpoofedSteve",
                UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3"),
                envelope);

        BedrockIdentityVerificationException error = assertThrows(
                BedrockIdentityVerificationException.class,
                () -> verifier(keyPair, "session-1").verify(profile));

        assertTrue(error.getMessage().contains("profile"));
    }

    @Test
    void verifiesLinkedJavaProfileBinding() throws Exception {
        KeyPair keyPair = ed25519KeyPair();
        String envelope = signedLinkedEnvelope(keyPair, "nonce-a", "session-1", "endpoint-id", "endpoint", "org-id");
        GameProfile profile = profileWithEnvelope(
                "JavaSteve",
                UUID.fromString("b5f7f978-5f58-4a21-b105-737f16c90785"),
                envelope);

        BedrockIdentityClaims claims = verifier(keyPair, "session-1").verify(profile);

        assertEquals("b5f7f978-5f58-4a21-b105-737f16c90785", claims.getLinkedJavaUuid());
        assertEquals("JavaSteve", claims.getLinkedJavaName());
    }

    private static BedrockIdentityVerifier verifier(KeyPair keyPair, String sessionId) {
        return BedrockIdentityVerifier.builder()
                .publicKey(keyPair.getPublic().getEncoded())
                .now(NOW)
                .endpointId("endpoint-id")
                .endpointName("endpoint")
                .orgId("org-id")
                .sessionId(sessionId)
                .protocol("bedrock")
                .build();
    }

    private static GameProfile profileWithEnvelope(String envelope) {
        return profileWithEnvelope(
                "BedrockSteve",
                UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3"),
                envelope);
    }

    private static GameProfile profileWithEnvelope(String username, UUID uniqueId, String envelope) {
        return new GameProfile(
                username,
                uniqueId,
                Arrays.asList(
                        new GameProfile.Property("textures", "skin", ""),
                        new GameProfile.Property(BedrockIdentityVerifier.PROPERTY_NAME, envelope, "")));
    }

    private static KeyPair ed25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] rawEd25519PublicKey(KeyPair keyPair) {
        EdECPublicKey publicKey = (EdECPublicKey) keyPair.getPublic();
        byte[] y = publicKey.getPoint().getY().toByteArray();
        byte[] raw = new byte[32];
        for (int source = y.length - 1, target = 0; source >= 0 && target < raw.length; source--, target++) {
            raw[target] = y[source];
        }
        if (publicKey.getPoint().isXOdd()) {
            raw[31] |= (byte) 0x80;
        }
        return raw;
    }

    private static String signedEnvelope(
            KeyPair keyPair,
            String nonce,
            String sessionId,
            String endpointId,
            String endpointName,
            String orgId) throws Exception {
        Envelope envelope = envelope(nonce, sessionId, endpointId, endpointName, orgId);
        return sign(keyPair, envelope);
    }

    private static String signedLinkedEnvelope(
            KeyPair keyPair,
            String nonce,
            String sessionId,
            String endpointId,
            String endpointName,
            String orgId) throws Exception {
        Envelope envelope = envelope(nonce, sessionId, endpointId, endpointName, orgId);
        envelope.policy.bedrock_auth_mode = "linked_java_only";
        envelope.principal.type = "bedrock_linked_java";
        envelope.principal.linked_java_uuid = "b5f7f978-5f58-4a21-b105-737f16c90785";
        envelope.principal.linked_java_name = "JavaSteve";
        return sign(keyPair, envelope);
    }

    private static Envelope envelope(
            String nonce,
            String sessionId,
            String endpointId,
            String endpointName,
            String orgId) {
        Envelope envelope = new Envelope();
        envelope.version = 1;
        envelope.issuer = "minekube-connect-test";
        envelope.endpoint = new Endpoint();
        envelope.endpoint.id = endpointId;
        envelope.endpoint.name = endpointName;
        envelope.endpoint.org_id = orgId;
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

        return envelope;
    }

    private static String sign(KeyPair keyPair, Envelope envelope) throws Exception {
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
        String linked_java_uuid;
        String linked_java_name;
    }
}
