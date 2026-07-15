package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerifiedBedrockIdentityRegistryTest {
    @Test
    void rejectsClaimsForAnotherSession() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        ConnectPlayer player = player("session-1");

        assertThrows(IllegalArgumentException.class, () -> registry.record(player, claims("session-2")));
        assertTrue(registry.get(player).isEmpty());
    }

    @Test
    void recordsAndRemovesClaimsForTheExactSession() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        ConnectPlayer player = player("session-1");

        registry.record(player, claims("session-1"));
        assertTrue(registry.get(player).isPresent());

        registry.remove(player);
        assertTrue(registry.get(player).isEmpty());
    }

    private static ConnectPlayer player(String sessionId) {
        return new ConnectPlayerImpl(
                sessionId,
                new GameProfile("BedrockSteve", UUID.randomUUID(), Collections.emptyList()),
                new Auth(false),
                "");
    }

    private static BedrockIdentityClaims claims(String sessionId) {
        Instant issuedAt = Instant.parse("2026-07-15T12:00:00Z");
        return new BedrockIdentityClaims(
                "minekube-connect",
                "endpoint-id",
                "endpoint",
                "org-id",
                sessionId,
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
                "nonce-a");
    }
}
