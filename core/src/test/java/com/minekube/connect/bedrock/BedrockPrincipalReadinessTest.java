package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.minekube.connect.config.ConnectConfig;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import minekube.connect.v1alpha1.WatchServiceOuterClass.PrincipalError;
import minekube.connect.v1alpha1.WatchServiceOuterClass.ReadinessAttestation;
import minekube.connect.v1alpha1.WatchServiceOuterClass.ReadinessChallenge;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;
import org.junit.jupiter.api.Test;

class BedrockPrincipalReadinessTest {
    private static final long NOW = 1_722_470_400L;

    @Test
    void advertisesOnlyGenerationTwoRequireWithUsableStaticPin() throws Exception {
        ConnectConfig ready = configured("require", 2, validPins());
        BedrockPrincipalReadiness readiness = readiness(ready);

        assertTrue(readiness.isReady());
        assertEquals(32, readiness.revision().length);
        assertEquals(BedrockPrincipalReadiness.CAPABILITY,
                readiness.capabilities(java.util.List.of(), BedrockPrincipalReadiness.Transport.WATCH).get(0));

        assertFalse(readiness(configured("warn", 2, validPins())).isReady());
        assertFalse(readiness(configured("require", 1, validPins())).isReady());
        assertFalse(readiness(configured("require", 2, Map.of())).isReady());
        assertFalse(readiness(configured("require", 2, Map.of("kid", "not-base64"))).isReady());
    }

    @Test
    void attestationEchoesValidChallengeAndFailsClosedForWrongTransport() throws Exception {
        BedrockPrincipalReadiness readiness = readiness(configured("require", 2, validPins()));
        ReadinessChallenge challenge = challenge(TunnelTransport.Type.TYPE_WEBSOCKET);

        ReadinessAttestation answer = readiness.attest(challenge, BedrockPrincipalReadiness.Transport.WATCH);
        assertEquals(challenge, answer.getChallenge());
        assertEquals(BedrockPrincipalReadiness.CAPABILITY, answer.getCapability());
        assertEquals("require", answer.getMode());
        assertEquals(32, answer.getReadinessRevision().size());
        assertEquals(NOW, answer.getObservedAtUnix());
        assertEquals(ReadinessAttestation.Result.RESULT_READY, answer.getResult());
        assertEquals(PrincipalError.PRINCIPAL_ERROR_UNSPECIFIED, answer.getReason());

        ReadinessAttestation refused = readiness.attest(challenge, BedrockPrincipalReadiness.Transport.LIBP2P);
        assertEquals(ReadinessAttestation.Result.RESULT_NOT_READY, refused.getResult());
        assertEquals(PrincipalError.PRINCIPAL_ERROR_READINESS, refused.getReason());
    }

    private static ReadinessChallenge challenge(TunnelTransport.Type transport) {
        return ReadinessChallenge.newBuilder()
                .setRequestId("request")
                .setNonce(ByteString.copyFrom(new byte[16]))
                .setEndpointId("endpoint-id")
                .setOrganizationId("organization-id")
                .setConnectorInstanceId("instance-id")
                .setLeaseId("lease-id")
                .setTransport(transport)
                .setPolicyRevision(7)
                .setIssuedAtUnix(NOW - 1)
                .setExpiresAtUnix(NOW + 29)
                .build();
    }

    private static Map<String, String> validPins() {
        return Map.of("kid-1", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
    }

    private static ConnectConfig configured(String mode, int generation, Map<String, String> pins) throws Exception {
        ConnectConfig config = new ConnectConfig();
        set(config.getBedrockPrincipal(), "configGeneration", generation);
        set(config.getBedrockPrincipal(), "mode", mode);
        set(config.getBedrockPrincipal(), "issuer", "minekube-connect");
        set(config.getBedrockPrincipal(), "trustDomain", "urn:minekube:connect:production");
        set(config.getBedrockPrincipal(), "audience", "urn:minekube:connect:bedrock-principal:v2");
        set(config.getBedrockPrincipal(), "metadataOrigin", "https://connect.minekube.com");
        set(config.getBedrockPrincipal(), "publicKeys", pins);
        return config;
    }

    private static BedrockPrincipalReadiness readiness(ConnectConfig config) {
        return new BedrockPrincipalReadiness(
                config, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
