package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.watch.SessionProposal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SimpleConnectApiBedrockIdentityTest {
    @Test
    void exposesOnlySanitizedPlayerAndNonceFreeVerifiedClaims() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        SimpleConnectApi api = new SimpleConnectApi(mock(ConnectLogger.class), registry);
        SessionProposal proposal = new SessionProposal(
                VerifiedBedrockIdentityRegistryTest.session(false),
                reason -> {});
        ConnectPlayer player = api.stageAdmission(proposal);
        api.addPlayer(player);
        BedrockIdentityClaims claims = VerifiedBedrockIdentityRegistryTest.claims("session-1");
        registry.record(player, claims);

        ConnectPlayer exposed = api.getPlayer(player.getUniqueId());

        assertEquals(1, exposed.getGameProfile().getProperties().size());
        assertEquals("textures", exposed.getGameProfile().getProperties().get(0).getName());
        assertFalse(exposed.getGameProfile().toString().contains("signed-envelope"));
        assertFalse(exposed.getGameProfile().toString().contains("replay-nonce-a"));
        assertFalse(exposed.getGameProfile().toString().contains("private-endpoint-id"));
        assertSame(claims, api.getVerifiedBedrockIdentity(exposed).orElseThrow());
        assertFalse(claims.toString().contains("replay-nonce-a"));
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
}
