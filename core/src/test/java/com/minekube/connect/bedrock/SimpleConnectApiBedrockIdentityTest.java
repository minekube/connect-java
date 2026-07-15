package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.watch.SessionProposal;
import java.lang.reflect.Method;
import java.util.Arrays;
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
        assertSame(claims, api.getVerifiedBedrockIdentity(exposed).orElseThrow());
        assertFalse(Arrays.stream(BedrockIdentityClaims.class.getMethods())
                .map(Method::getName)
                .anyMatch("getNonce"::equals));
        assertFalse(claims.toString().contains("replay-nonce-a"));
    }
}
