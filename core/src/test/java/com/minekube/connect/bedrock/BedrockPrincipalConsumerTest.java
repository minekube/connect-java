package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import com.minekube.connect.api.player.principal.PrincipalError;
import com.minekube.connect.config.ConnectConfig;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import org.junit.jupiter.api.Test;

class BedrockPrincipalConsumerTest {
    @Test
    void verifiesWireEnvelopeAndAppliesOnlyEffectiveLinkedProfile() throws Exception {
        JsonObject vector = vector("valid-linked");
        BedrockPrincipalConsumer consumer = consumer(vector);
        Session session = session(vector);

        var principal = consumer.verify(session).orElseThrow();
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                principal.effectiveGameProfile().uuid());
        assertEquals("JavaOne", principal.effectiveGameProfile().name());

        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry, consumer(vector));
        try {
            var proposal = coordinator.proposal(session, ignored -> {}, "", "");
            var player = coordinator.stage(proposal);
            assertEquals(principal.effectiveGameProfile().uuid(), player.getUniqueId());
            assertEquals(principal.effectiveGameProfile().name(), player.getUsername());
            assertTrue(player.getGameProfile().getProperties().isEmpty());
            assertTrue(registry.getPrincipal(player).isEmpty());

            var decision = coordinator.verify(player, proposal.getAdmissionToken(),
                    new BedrockIdentityEnforcer(
                            config(), org.mockito.Mockito.mock(com.minekube.connect.api.logger.ConnectLogger.class),
                            () -> Instant.ofEpochSecond(vector.get("verification_time_unix").getAsLong())),
                    "", "", SessionProtocol.SESSION_PROTOCOL_BEDROCK);
            assertTrue(decision.allowed());
            assertTrue(registry.getPrincipal(player).isPresent());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void malformedCompanionBindingFailsBeforeProfileApplication() throws Exception {
        JsonObject vector = vector("valid-unlinked");
        Session malformed = session(vector).toBuilder().clearConnectSessionNonce().build();
        BedrockPrincipalAdmissionException error = assertThrows(
                BedrockPrincipalAdmissionException.class,
                () -> consumer(vector).verify(malformed));
        assertEquals(PrincipalError.BINDING_MISMATCH, error.error());
        assertFalse(error.toString().contains(vector.get("compact_jws").getAsString()));
    }

    @Test
    void requireModeRejectsSessionsMissingV2Principal() throws Exception {
        JsonObject vector = vector("valid-unlinked");
        BedrockPrincipalAdmissionException error = assertThrows(
                BedrockPrincipalAdmissionException.class,
                () -> consumer(vector).verify(session(vector).toBuilder()
                        .clearSignedBedrockPrincipalV2().build()));
        assertEquals(PrincipalError.READINESS, error.error());
    }

    @Test
    void oversizedV2EnvelopeIsRejected() throws Exception {
        JsonObject vector = vector("valid-unlinked");
        BedrockPrincipalAdmissionException error = assertThrows(
                BedrockPrincipalAdmissionException.class,
                () -> consumer(vector).verify(session(vector).toBuilder()
                        .setSignedBedrockPrincipalV2(ByteString.copyFrom(new byte[16 * 1024 + 1]))
                        .build()));
        assertEquals(PrincipalError.MALFORMED, error.error());
    }

    private static BedrockPrincipalConsumer consumer(JsonObject vector) {
        return new BedrockPrincipalConsumer(config(), Clock.fixed(
                Instant.ofEpochSecond(vector.get("verification_time_unix").getAsLong()), ZoneOffset.UTC));
    }

    private static ConnectConfig config() {
        ConnectConfig config = new ConnectConfig();
        Object principal = config.getBedrockPrincipal();
        set(principal, "configGeneration", 2);
        set(principal, "mode", "require");
        set(principal, "issuer", "minekube-connect-test");
        set(principal, "trustDomain", "urn:minekube:connect:test:corpus-v2");
        set(principal, "audience", "urn:minekube:connect:test:bedrock-principal:v2");
        set(principal, "metadataOrigin", "https://metadata.example");
        set(principal, "publicKeys", Map.of(
                "connect-v2-test", "diQm8c6MI-Zwn1nie8hq4wqf3mYLuI96uJBC6NHCTDg"));
        return config;
    }

    private static Session session(JsonObject vector) {
        JsonObject context = vector.getAsJsonObject("trusted_context");
        return Session.newBuilder()
                .setId(context.get("connect_session_id").getAsString())
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(GameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000099")
                                .setName("UntrustedCarrier")))
                .setAuth(Authentication.newBuilder().setPassthrough(false))
                .setProtocol(SessionProtocol.SESSION_PROTOCOL_BEDROCK)
                .setEndpointId(context.get("endpoint_id").getAsString())
                .setOrganizationId(context.get("organization_id").getAsString())
                .setConnectSessionNonce(ByteString.copyFrom(Base64.getUrlDecoder()
                        .decode(context.get("connect_session_nonce").getAsString())))
                .setSourceProtocolVersion(context.get("source_protocol_version").getAsInt())
                .setPolicyRevision(context.get("policy_revision").getAsLong())
                .setSignedBedrockPrincipalV2(ByteString.copyFromUtf8(
                        vector.get("compact_jws").getAsString()))
                .build();
    }

    private JsonObject vector(String name) {
        try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/bedrock-principal-v2/core-vectors.json")), StandardCharsets.UTF_8)) {
            for (var value : JsonParser.parseReader(reader).getAsJsonArray()) {
                if (name.equals(value.getAsJsonObject().get("name").getAsString())) {
                    return value.getAsJsonObject();
                }
            }
            throw new AssertionError("missing vector " + name);
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
