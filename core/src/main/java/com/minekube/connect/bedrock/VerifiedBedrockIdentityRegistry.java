package com.minekube.connect.bedrock;

import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.player.ConnectPlayerImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;

@Singleton
public final class VerifiedBedrockIdentityRegistry implements AutoCloseable {
    private static final long ADMISSION_PROFILE_TTL_SECONDS = 30;

    private final Map<String, BedrockIdentityClaims> identities = new ConcurrentHashMap<>();
    private final Map<String, AdmissionProfile> admissionProfiles = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;
    private boolean closed;

    public VerifiedBedrockIdentityRegistry() {
        this(newCleanupExecutor());
    }

    VerifiedBedrockIdentityRegistry(ScheduledExecutorService cleanupExecutor) {
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
    }

    public synchronized ConnectPlayer stage(Session session) {
        ensureOpen();
        Objects.requireNonNull(session, "session");
        Player player = session.getPlayer();
        GameProfile rawProfile = new GameProfile(
                player.getProfile().getName(),
                java.util.UUID.fromString(player.getProfile().getId()),
                Collections.unmodifiableList(player.getProfile().getPropertiesList().stream()
                        .map(property -> new GameProfile.Property(
                                property.getName(),
                                property.getValue(),
                                property.getSignature()))
                        .collect(java.util.stream.Collectors.toList())));
        ConnectPlayer rawPlayer = new ConnectPlayerImpl(
                session.getId(),
                rawProfile,
                new Auth(session.getAuth().getPassthrough()),
                "");
        ConnectPlayer publicPlayer = publicPlayer(rawPlayer);
        String sessionId = rawPlayer.getSessionId();
        identities.remove(sessionId);
        removeAdmissionProfile(sessionId);
        if (hasPrivateIdentity(rawProfile)) {
            Object cleanupToken = new Object();
            AdmissionProfile admissionProfile = new AdmissionProfile(
                    copy(rawProfile), cleanupToken, publicPlayer);
            admissionProfiles.put(sessionId, admissionProfile);
            try {
                admissionProfile.cleanup = cleanupExecutor.schedule(
                        () -> expire(sessionId, cleanupToken),
                        ADMISSION_PROFILE_TTL_SECONDS,
                        TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                if (admissionProfiles.remove(sessionId, admissionProfile)) {
                    clearAdmissionProfile(admissionProfile);
                }
                throw e;
            }
        }
        return publicPlayer;
    }

    public ConnectPlayer publicPlayer(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        GameProfile publicProfile = BedrockIdentityProfiles.withoutEnvelope(player.getGameProfile());
        if (publicProfile == player.getGameProfile()) {
            return player;
        }
        return new ConnectPlayerImpl(
                player.getSessionId(),
                publicProfile,
                player.getAuth(),
                player.getLanguageTag());
    }

    synchronized Optional<GameProfile> takeAdmissionProfile(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        AdmissionProfile admissionProfile = admissionProfiles.remove(player.getSessionId());
        GameProfile profile = admissionProfile == null ? null : admissionProfile.profile;
        clearAdmissionProfile(admissionProfile);
        return Optional.ofNullable(profile);
    }

    synchronized void clearClaims(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        identities.remove(player.getSessionId());
    }

    synchronized void record(ConnectPlayer player, BedrockIdentityClaims claims) {
        ensureOpen();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(claims, "claims");
        if (!player.getSessionId().equals(claims.getSessionId())) {
            throw new IllegalArgumentException("Bedrock identity claims session mismatch");
        }
        identities.put(player.getSessionId(), claims);
    }

    public Optional<BedrockIdentityClaims> get(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        return Optional.ofNullable(identities.get(player.getSessionId()));
    }

    public synchronized void remove(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        identities.remove(player.getSessionId());
        removeAdmissionProfile(player.getSessionId());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        admissionProfiles.values().forEach(VerifiedBedrockIdentityRegistry::clearAdmissionProfile);
        admissionProfiles.clear();
        identities.clear();
        cleanupExecutor.shutdownNow();
    }

    private static GameProfile copy(GameProfile profile) {
        return new GameProfile(
                profile.getUsername(),
                profile.getUniqueId(),
                Collections.unmodifiableList(new ArrayList<>(profile.getProperties())));
    }

    private static boolean hasPrivateIdentity(GameProfile profile) {
        return profile.getProperties().stream()
                .anyMatch(property ->
                        BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()) ||
                                BedrockIdentityProfiles.SCOPE_PROPERTY_NAME.equals(property.getName()));
    }

    private synchronized void expire(String sessionId, Object cleanupToken) {
        AdmissionProfile current = admissionProfiles.get(sessionId);
        if (current != null && current.cleanupToken == cleanupToken &&
                admissionProfiles.remove(sessionId, current)) {
            clearAdmissionProfile(current);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Bedrock identity registry is closed");
        }
    }

    private static void cancelCleanup(AdmissionProfile admissionProfile) {
        if (admissionProfile != null && admissionProfile.cleanup != null) {
            admissionProfile.cleanup.cancel(false);
        }
    }

    private synchronized AdmissionSnapshot beginAdmission(ConnectPlayer player) {
        AdmissionProfile generation = admissionProfiles.get(player.getSessionId());
        if (closed || generation == null || generation.player != player || generation.profile == null) {
            return null;
        }
        GameProfile profile = generation.profile;
        generation.profile = null;
        identities.remove(player.getSessionId());
        return new AdmissionSnapshot(generation, profile);
    }

    private synchronized boolean completeAdmission(
            AdmissionProfile generation,
            BedrockIdentityClaims claims) {
        String sessionId = generation.player.getSessionId();
        if (closed || admissionProfiles.get(sessionId) != generation ||
                !admissionProfiles.remove(sessionId, generation)) {
            clearAdmissionProfile(generation);
            return false;
        }
        clearAdmissionProfile(generation);
        if (claims != null) {
            if (!sessionId.equals(claims.getSessionId())) {
                throw new IllegalArgumentException("Bedrock identity claims session mismatch");
            }
            identities.put(sessionId, claims);
        }
        return true;
    }

    private synchronized void abandonAdmission(AdmissionProfile generation) {
        String sessionId = generation.player.getSessionId();
        admissionProfiles.remove(sessionId, generation);
        clearAdmissionProfile(generation);
    }

    private void removeAdmissionProfile(String sessionId) {
        clearAdmissionProfile(admissionProfiles.remove(sessionId));
    }

    private static void clearAdmissionProfile(AdmissionProfile admissionProfile) {
        if (admissionProfile == null) {
            return;
        }
        admissionProfile.profile = null;
        cancelCleanup(admissionProfile);
    }

    private static ScheduledExecutorService newCleanupExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "connect-bedrock-admission-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static final class AdmissionProfile {
        private GameProfile profile;
        private final Object cleanupToken;
        private final ConnectPlayer player;
        private ScheduledFuture<?> cleanup;

        private AdmissionProfile(
                GameProfile profile,
                Object cleanupToken,
                ConnectPlayer player) {
            this.profile = profile;
            this.cleanupToken = cleanupToken;
            this.player = player;
        }
    }

    private static final class AdmissionSnapshot {
        private final AdmissionProfile generation;
        private GameProfile profile;

        private AdmissionSnapshot(AdmissionProfile generation, GameProfile profile) {
            this.generation = generation;
            this.profile = profile;
        }

        private GameProfile takeProfile() {
            GameProfile current = profile;
            profile = null;
            return current;
        }
    }
}
