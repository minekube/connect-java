package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.gson.Gson;
import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.network.netty.LocalSession;
import com.minekube.connect.player.ConnectPlayerImpl;
import com.minekube.connect.watch.SessionProposal;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimpleConnectApiBedrockIdentityTest {
    @Test
    void keepsRegistryAndAdmissionCapabilityOutOfPublicApis() {
        for (Class<?> type : List.of(SimpleConnectApi.class, LocalSession.Context.class)) {
            for (Method method : type.getMethods()) {
                assertFalse(VerifiedBedrockIdentityRegistry.class.isAssignableFrom(method.getReturnType()));
                assertFalse(Arrays.stream(method.getParameterTypes())
                        .anyMatch(VerifiedBedrockIdentityRegistry.class::isAssignableFrom));
            }
        }
        assertFalse(Arrays.stream(LocalSession.Context.class.getDeclaredFields())
                .anyMatch(field -> VerifiedBedrockIdentityRegistry.class.isAssignableFrom(field.getType())));

        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        try {
            SimpleConnectApi api = new SimpleConnectApi(mock(ConnectLogger.class), registry);
            ConnectPlayer player = api.stageAdmission(new SessionProposal(
                    VerifiedBedrockIdentityRegistryTest.session(false),
                    reason -> {}));

            assertNoPrivateIdentityReachable(player);
            api.addPlayer(player);
            assertNoPrivateIdentityReachable(api.getPlayer(player.getUniqueId()));
        } finally {
            registry.close();
        }
    }

    @Test
    void sameUuidReplacementRemovesDisplacedSessionClaims() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        SimpleConnectApi api = new SimpleConnectApi(mock(ConnectLogger.class), registry);
        UUID uuid = UUID.randomUUID();
        ConnectPlayer first = player("session-a", uuid);
        ConnectPlayer second = player("session-b", uuid);
        registry.record(first, VerifiedBedrockIdentityRegistryTest.claims("session-a"));
        registry.record(second, VerifiedBedrockIdentityRegistryTest.claims("session-b"));

        api.addPlayer(first);
        api.addPlayer(second);

        assertFalse(registry.get(first).isPresent());
        assertTrue(registry.get(second).isPresent());
        api.playerRemoved(uuid);
        assertFalse(registry.get(second).isPresent());
    }
    @Test
    void exposesOnlySanitizedPlayerAndNonceFreeVerifiedClaims() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        SimpleConnectApi api = new SimpleConnectApi(mock(ConnectLogger.class), registry);
        SessionProposal proposal = new SessionProposal(
                VerifiedBedrockIdentityRegistryTest.session(false),
                reason -> {});
        ConnectPlayer player = api.stageAdmission(proposal);
        BedrockIdentityClaims claims = VerifiedBedrockIdentityRegistryTest.claims("session-1");
        registry.record(player, claims);
        assertTrue(api.getVerifiedBedrockIdentity(player).isEmpty());
        api.addPlayer(player);

        ConnectPlayer exposed = api.getPlayer(player.getUniqueId());

        assertEquals(1, exposed.getGameProfile().getProperties().size());
        assertEquals("textures", exposed.getGameProfile().getProperties().get(0).getName());
        assertFalse(exposed.getGameProfile().toString().contains("signed-envelope"));
        assertFalse(exposed.getGameProfile().toString().contains("replay-nonce-a"));
        assertFalse(exposed.getGameProfile().toString().contains("private-endpoint-id"));
        assertSame(claims, api.getVerifiedBedrockIdentity(exposed).orElseThrow());
        assertFalse(claims.toString().contains("replay-nonce-a"));
        api.playerRemoved(player.getUniqueId());
        assertTrue(api.getVerifiedBedrockIdentity(exposed).isEmpty());
    }

    @SuppressWarnings("deprecation")
    @Test
    void preservesNonceApiDescriptorWithoutExposingReplayMaterial() throws Exception {
        Instant issuedAt = Instant.parse("2026-07-15T12:00:00Z");
        BedrockIdentityClaims claims = new BedrockIdentityClaims(
                "issuer",
                "endpoint-id",
                "endpoint",
                "org-id",
                "session-1",
                "bedrock",
                "trusted_bedrock_xuid",
                "bedrock_xuid",
                "2533274790395904",
                "BedrockSteve",
                "f912bf90-8349-565f-9dc0-9891923c0cc3",
                null,
                null,
                issuedAt,
                issuedAt.plusSeconds(300),
                "replay-nonce-a");

        assertNotNull(BedrockIdentityClaims.class.getConstructor(
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Instant.class,
                Instant.class,
                String.class));
        assertNull(claims.getNonce());
        assertFalse(claims.toString().contains("replay-nonce-a"));
    }

    @Test
    void discardsPrivateAdmissionStateWhenSetupFails() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        SimpleConnectApi api = new SimpleConnectApi(mock(ConnectLogger.class), registry);
        ConnectPlayer player = api.stageAdmission(new SessionProposal(
                VerifiedBedrockIdentityRegistryTest.session(false),
                reason -> {}));

        api.discardAdmission(player);

        assertFalse(registry.takeAdmissionProfile(player).isPresent());
    }

    private static ConnectPlayer player(String sessionId, UUID uuid) {
        return new ConnectPlayerImpl(sessionId,
                new GameProfile("BedrockSteve", uuid, Collections.emptyList()), new Auth(false), "");
    }

    private static void assertNoPrivateIdentityReachable(ConnectPlayer player) {
        assertSame(ConnectPlayerImpl.class, player.getClass());
        assertTrue(Modifier.isFinal(player.getClass().getModifiers()));
        assertTrue(Arrays.stream(player.getClass().getDeclaredFields())
                .noneMatch(field ->
                        VerifiedBedrockIdentityRegistry.class.isAssignableFrom(field.getType()) ||
                                Map.class.isAssignableFrom(field.getType()) ||
                                field.getName().contains("admission") ||
                                field.getName().contains("claims") ||
                                field.getName().contains("registry")));

        String serialized = new Gson().toJson(player);
        for (String privateValue : List.of(
                "signed-envelope",
                "replay-nonce-a",
                "private-endpoint-id",
                "VerifiedBedrockIdentityRegistry",
                "admissionProfiles",
                "identities")) {
            assertFalse(serialized.contains(privateValue));
        }
    }
}
