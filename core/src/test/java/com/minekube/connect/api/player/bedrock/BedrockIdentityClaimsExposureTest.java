package com.minekube.connect.api.player.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BedrockIdentityClaimsExposureTest {
    @Test
    void exposesXuidOnlyThroughTheExplicitSensitiveGetter() {
        String xuid = "12345678901234567";
        BedrockIdentityClaims claims = new BedrockIdentityClaims(
                "minekube-connect", "endpoint-id", "endpoint", "org-id", "session-id",
                "bedrock", "trusted_bedrock_xuid", "unlinked", xuid, "BedrockSteve",
                "f912bf90-8349-565f-9dc0-9891923c0cc3", null, null,
                null, null);

        assertEquals(xuid, claims.getBedrockXuid());
        assertFalse(claims.toString().contains(xuid));
        assertFalse(new GsonBuilder()
                .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                        (value, type, context) -> new JsonPrimitive(value.toString()))
                .create()
                .toJson(claims)
                .contains(xuid));
        assertFalse(claims.toString().contains("signed-envelope"));
        assertFalse(claims.toString().contains("replay-nonce"));
        assertFalse(claims.toString().contains("bedrock_identity_scope"));
    }
}
