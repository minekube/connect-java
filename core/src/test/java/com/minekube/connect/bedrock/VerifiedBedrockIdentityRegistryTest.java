package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfileProperty;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import org.junit.jupiter.api.Test;

class VerifiedBedrockIdentityRegistryTest {
    @Test
    void hasNoStaticAdmissionOwnerBridge() {
        assertThrows(NoSuchFieldException.class,
                () -> VerifiedBedrockIdentityRegistry.class.getDeclaredField("ADMISSION_OWNERS"));
    }

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

    @Test
    void stagesEnvelopeOnlyForPrivateSingleUseAdmission() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();

        ConnectPlayer player = registry.stage(session(false));

        assertEquals(1, player.getGameProfile().getProperties().size());
        assertEquals("textures", player.getGameProfile().getProperties().get(0).getName());
        assertFalse(player.getGameProfile().toString().contains("replay-nonce-a"));
        assertFalse(player.getGameProfile().toString().contains("private-endpoint-id"));
        GameProfile admissionProfile = registry.takeAdmissionProfile(player).orElseThrow();
        assertTrue(admissionProfile.toString().contains("replay-nonce-a"));
        assertTrue(admissionProfile.toString().contains("private-endpoint-id"));
        assertTrue(registry.takeAdmissionProfile(player).isEmpty());
    }

    @Test
    void retainsPrivateIdentityForPassthroughAdmissionOnly() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();

        ConnectPlayer player = registry.stage(session(true));

        assertFalse(player.getGameProfile().toString().contains("replay-nonce-a"));
        assertFalse(player.getGameProfile().toString().contains("private-endpoint-id"));
        GameProfile admissionProfile = registry.takeAdmissionProfile(player).orElseThrow();
        assertTrue(admissionProfile.toString().contains("replay-nonce-a"));
        assertTrue(admissionProfile.toString().contains("private-endpoint-id"));
        assertTrue(registry.takeAdmissionProfile(player).isEmpty());
    }

    @Test
    void clearsStagedEnvelopeWhenSessionIsRestagedWithoutPrivateIdentity() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        registry.stage(session(false));

        ConnectPlayer restaged = registry.stage(sessionWithoutPrivateIdentity());

        assertTrue(registry.takeAdmissionProfile(restaged).isEmpty());
    }

    @Test
    void expiresStagedEnvelopeWithoutRemovingItsReplacement() {
        ScheduledExecutorService cleanupExecutor = mock(ScheduledExecutorService.class);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry(cleanupExecutor);
        registry.stage(session(false));
        ConnectPlayer replacement = registry.stage(session(true));
        ConnectPlayer expiring = registry.stage(session(false).toBuilder().setId("session-2").build());
        org.mockito.ArgumentCaptor<Runnable> cleanups = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        org.mockito.ArgumentCaptor<Long> delays = org.mockito.ArgumentCaptor.forClass(Long.class);

        verify(cleanupExecutor, org.mockito.Mockito.times(3))
                .schedule(cleanups.capture(), delays.capture(), eq(TimeUnit.SECONDS));
        assertEquals(java.util.Arrays.asList(30L, 30L, 30L), delays.getAllValues());
        cleanups.getAllValues().get(0).run();

        assertTrue(registry.takeAdmissionProfile(replacement).isPresent());
        cleanups.getAllValues().get(2).run();
        assertTrue(registry.takeAdmissionProfile(expiring).isEmpty());
    }

    @Test
    void consumingAdmissionCancelsItsPendingCleanup() {
        ScheduledThreadPoolExecutor cleanupExecutor = new ScheduledThreadPoolExecutor(1);
        cleanupExecutor.setRemoveOnCancelPolicy(true);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry(cleanupExecutor);
        try {
            ConnectPlayer player = registry.stage(session(false));
            assertEquals(1, cleanupExecutor.getQueue().size());

            assertTrue(registry.takeAdmissionProfile(player).isPresent());

            assertTrue(cleanupExecutor.getQueue().isEmpty());
        } finally {
            registry.close();
        }
    }

    @Test
    void closeClearsPrivateStateAndAllowsCleanReload() {
        ScheduledThreadPoolExecutor cleanupExecutor = new ScheduledThreadPoolExecutor(1);
        cleanupExecutor.setRemoveOnCancelPolicy(true);
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry(cleanupExecutor);
        ConnectPlayer player = registry.stage(session(false));
        registry.record(player, claims("session-1"));

        registry.close();

        assertTrue(cleanupExecutor.isShutdown());
        assertTrue(cleanupExecutor.getQueue().isEmpty());
        assertTrue(registry.takeAdmissionProfile(player).isEmpty());
        assertTrue(registry.get(player).isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.stage(session(false)));

        ScheduledThreadPoolExecutor reloadExecutor = new ScheduledThreadPoolExecutor(1);
        reloadExecutor.setRemoveOnCancelPolicy(true);
        VerifiedBedrockIdentityRegistry reloaded = new VerifiedBedrockIdentityRegistry(reloadExecutor);
        try {
            ConnectPlayer reloadedPlayer = reloaded.stage(session(false));
            assertTrue(reloaded.takeAdmissionProfile(reloadedPlayer).isPresent());
        } finally {
            reloaded.close();
        }
        assertTrue(reloadExecutor.isShutdown());
    }

    private static ConnectPlayer player(String sessionId) {
        return new ConnectPlayerImpl(
                sessionId,
                new GameProfile("BedrockSteve", UUID.randomUUID(), Collections.emptyList()),
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

    private static Session sessionWithoutPrivateIdentity() {
        Session source = session(false);
        minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile profile =
                source.getPlayer().getProfile().toBuilder()
                        .clearProperties()
                        .addProperties(GameProfileProperty.newBuilder()
                                .setName("textures")
                                .setValue("skin"))
                        .build();
        return source.toBuilder()
                .setPlayer(source.getPlayer().toBuilder().setProfile(profile))
                .build();
    }
}
