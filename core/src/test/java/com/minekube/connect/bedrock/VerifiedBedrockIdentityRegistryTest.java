package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfileProperty;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import org.junit.jupiter.api.Test;

class VerifiedBedrockIdentityRegistryTest {
    @Test
    void containsClaimsOnlyStateAndNoAdmissionLifecycle() {
        for (Field field : VerifiedBedrockIdentityRegistry.class.getDeclaredFields()) {
            assertFalse(ExecutorService.class.isAssignableFrom(field.getType()));
            assertFalse(Session.class.isAssignableFrom(field.getType()));
            assertFalse(field.getName().toLowerCase().contains("admission"));
        }
        for (Method method : VerifiedBedrockIdentityRegistry.class.getDeclaredMethods()) {
            assertFalse(Session.class.isAssignableFrom(method.getReturnType()));
            assertFalse(Arrays.stream(method.getParameterTypes()).anyMatch(Session.class::isAssignableFrom));
            assertFalse(method.getName().toLowerCase().contains("admission"));
        }
        assertThrows(NoSuchFieldException.class,
                () -> VerifiedBedrockIdentityRegistry.class.getDeclaredField("ADMISSION_OWNERS"));
    }

    @Test
    void rejectsClaimsForAnotherSession() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        ConnectPlayer player = player("session-1");

        assertThrows(IllegalArgumentException.class,
                () -> registry.record(player, claims("session-2")));
        assertTrue(registry.get(player).isEmpty());
    }

    @Test
    void bindsClaimsToExactPlayerIdentity() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        UUID uuid = UUID.randomUUID();
        ConnectPlayer player = player("session-1", uuid);
        ConnectPlayer equalPlayer = player("session-1", uuid);
        BedrockIdentityClaims claims = claims("session-1");

        assertTrue(player.equals(equalPlayer));
        registry.record(player, claims);

        assertSame(claims, registry.get(player).orElseThrow());
        assertTrue(registry.get(equalPlayer).isEmpty());
        registry.remove(equalPlayer);
        assertSame(claims, registry.get(player).orElseThrow());
    }

    @Test
    void recordsAndRemovesClaimsForTheExactPlayer() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        ConnectPlayer player = player("session-1");

        registry.record(player, claims("session-1"));
        assertTrue(registry.get(player).isPresent());

        registry.remove(player);
        assertTrue(registry.get(player).isEmpty());
    }

    @Test
    void closeClearsClaimsAndRejectsFurtherRecords() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        ConnectPlayer player = player("session-1");
        registry.record(player, claims("session-1"));

        registry.close();

        assertTrue(registry.get(player).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> registry.record(player, claims("session-1")));
    }

    private static ConnectPlayer player(String sessionId) {
        return player(sessionId, UUID.randomUUID());
    }

    private static ConnectPlayer player(String sessionId, UUID uuid) {
        return new ConnectPlayerImpl(
                sessionId,
                new GameProfile("BedrockSteve", uuid, Collections.emptyList()),
                new Auth(false),
                "");
    }

    static BedrockIdentityClaims claims(String sessionId) {
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
                issuedAt.plusSeconds(300));
    }

    static Session session(boolean passthrough) {
        return Session.newBuilder()
                .setId("session-1")
                .setAuth(Authentication.newBuilder().setPassthrough(passthrough))
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile.newBuilder()
                                .setId("f912bf90-8349-565f-9dc0-9891923c0cc3")
                                .setName("BedrockSteve")
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("textures")
                                        .setValue("skin"))
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity")
                                        .setValue("signed-envelope-replay-nonce-a"))
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity_scope")
                                        .setValue("private-endpoint-id"))))
                .build();
    }
}
