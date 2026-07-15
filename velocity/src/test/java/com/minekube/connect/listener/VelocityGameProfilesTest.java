package com.minekube.connect.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.player.ConnectPlayerImpl;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.GameProfile.Property;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VelocityGameProfilesTest {
    @Test
    void forwardedProfileExcludesIdentityEnvelopeAndNonceFromEverySource() {
        UUID uuid = UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3");
        GameProfile base = new GameProfile(
                UUID.randomUUID(),
                "Original",
                Arrays.asList(
                        new Property("base", "value", ""),
                        new Property(
                                BedrockIdentityVerifier.PROPERTY_NAME,
                                "base-envelope-replay-nonce-a",
                                ""),
                        new Property(
                                "minekube:bedrock_identity_scope",
                                "base-private-endpoint-a",
                                "")));
        ConnectPlayer player = new ConnectPlayerImpl(
                "session-1",
                new com.minekube.connect.api.player.GameProfile(
                        "BedrockSteve",
                        uuid,
                        Arrays.asList(
                                new com.minekube.connect.api.player.GameProfile.Property(
                                        "textures",
                                        "skin",
                                        "signature"),
                                new com.minekube.connect.api.player.GameProfile.Property(
                                        BedrockIdentityVerifier.PROPERTY_NAME,
                                        "connect-envelope-replay-nonce-b",
                                        ""),
                                new com.minekube.connect.api.player.GameProfile.Property(
                                        "minekube:bedrock_identity_scope",
                                        "connect-private-endpoint-b",
                                        ""))),
                new Auth(false),
                "");

        GameProfile forwarded = VelocityGameProfiles.fromConnectPlayer(base, player);

        assertEquals(uuid, forwarded.getId());
        assertEquals("BedrockSteve", forwarded.getName());
        assertFalse(forwarded.getProperties().stream()
                .anyMatch(property -> BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName())));
        assertFalse(forwarded.getProperties().stream()
                .anyMatch(property -> "minekube:bedrock_identity_scope".equals(property.getName())));
        assertFalse(forwarded.toString().contains("replay-nonce"));
        assertFalse(forwarded.toString().contains("private-endpoint"));
    }
}
