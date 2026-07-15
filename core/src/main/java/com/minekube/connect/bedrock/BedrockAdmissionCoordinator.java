package com.minekube.connect.bedrock;

import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.player.ConnectPlayerImpl;
import com.minekube.connect.watch.SessionProposal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;

/** Owns raw admission input until the single verification attempt consumes it. */
@Singleton
public final class BedrockAdmissionCoordinator implements AutoCloseable {
    private static final long ADMISSION_TTL_SECONDS = 30;

    private final VerifiedBedrockIdentityRegistry identities;
    private final ScheduledExecutorService cleanupExecutor;
    private final Map<AdmissionToken, Admission> admissions = new java.util.HashMap<>();
    private final Map<ConnectPlayer, AdmissionToken> players = new IdentityHashMap<>();
    private boolean closed;

    @Inject
    public BedrockAdmissionCoordinator(VerifiedBedrockIdentityRegistry identities) {
        this(identities, newCleanupExecutor());
    }

    BedrockAdmissionCoordinator(
            VerifiedBedrockIdentityRegistry identities,
            ScheduledExecutorService cleanupExecutor) {
        this.identities = Objects.requireNonNull(identities, "identities");
        this.cleanupExecutor = Objects.requireNonNull(cleanupExecutor, "cleanupExecutor");
    }

    public synchronized SessionProposal proposal(
            Session raw,
            java.util.function.Consumer<com.google.rpc.Status> reject,
            String endpointId,
            String endpointOrgId) {
        AdmissionToken token = null;
        if (hasPrivateIdentity(raw)) {
            ensureOpen();
            AdmissionToken createdToken = new AdmissionToken();
            Admission admission = new Admission(raw, createdToken);
            admissions.put(createdToken, admission);
            try {
                admission.cleanup = cleanupExecutor.schedule(
                        () -> expire(createdToken, admission), ADMISSION_TTL_SECONDS, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                admissions.remove(createdToken);
                throw e;
            }
            token = createdToken;
        }
        return new SessionProposal(raw, reject, endpointId, endpointOrgId, token);
    }

    public synchronized ConnectPlayer stage(SessionProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        Session session = proposal.getSession();
        Admission admission = proposal.getAdmissionToken() == null
                ? null : admissions.get(proposal.getAdmissionToken());
        ConnectPlayer player = playerFor(admission == null ? session : admission.raw);
        ConnectPlayer publicPlayer = identities.publicPlayer(player);
        if (admission != null) {
            admissions.entrySet().removeIf(entry -> {
                Admission existing = entry.getValue();
                if (existing != admission && existing.player != null &&
                        existing.player.getSessionId().equals(publicPlayer.getSessionId())) {
                    players.remove(existing.player);
                    clear(existing);
                    return true;
                }
                return false;
            });
            admission.player = publicPlayer;
            players.put(publicPlayer, proposal.getAdmissionToken());
        }
        return publicPlayer;
    }

    public BedrockIdentityEnforcer.Decision verify(
            ConnectPlayer player,
            AdmissionToken token,
            BedrockIdentityEnforcer enforcer,
            String endpointId,
            String endpointOrgId,
            SessionProtocol protocol) {
        if (token == null) {
            return null;
        }
        Admission admission;
        GameProfile rawProfile;
        synchronized (this) {
            admission = admissions.get(token);
            if (closed || admission == null || admission.player != player ||
                    admission.state != AdmissionState.PENDING) {
                return enforcer.invalidatedAdmission();
            }
            admission.state = AdmissionState.VERIFYING;
            rawProfile = profile(admission.raw.getPlayer());
            admission.raw = null;
        }
        BedrockIdentityEnforcer.Decision decision;
        try {
            decision = enforcer.verifyAdmissionSnapshot(
                    player, rawProfile, endpointId, endpointOrgId, protocol);
        } catch (RuntimeException | Error e) {
            synchronized (this) {
                if (admissions.remove(token, admission)) {
                    players.remove(player);
                    clear(admission);
                }
            }
            throw e;
        }
        synchronized (this) {
            if (closed || admissions.get(token) != admission ||
                    admission.state != AdmissionState.VERIFYING) {
                return enforcer.invalidatedAdmission();
            }
            admissions.remove(token);
            players.remove(player);
            cancel(admission);
            admission.state = AdmissionState.CONSUMED;
            if (decision.verifiedClaims() != null) {
                identities.record(player, decision.verifiedClaims());
            }
            return decision;
        }
    }

    public synchronized void discard(ConnectPlayer player) {
        AdmissionToken token = players.remove(player);
        if (token != null) {
            clear(admissions.remove(token));
        }
        identities.remove(player);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        admissions.values().forEach(BedrockAdmissionCoordinator::clear);
        admissions.clear();
        players.clear();
        identities.close();
        cleanupExecutor.shutdownNow();
    }

    private synchronized void expire(AdmissionToken token, Admission expected) {
        if (admissions.remove(token, expected)) {
            if (expected.player != null) players.remove(expected.player);
            clear(expected);
        }
    }

    public static ConnectPlayer playerFor(Session session) {
        Player player = session.getPlayer();
        return new ConnectPlayerImpl(
                session.getId(),
                profile(player),
                new Auth(session.getAuth().getPassthrough()),
                "");
    }

    private static GameProfile profile(Player player) {
        return new GameProfile(
                player.getProfile().getName(),
                java.util.UUID.fromString(player.getProfile().getId()),
                Collections.unmodifiableList(new ArrayList<>(player.getProfile().getPropertiesList().stream()
                        .map(property -> new GameProfile.Property(property.getName(), property.getValue(), property.getSignature()))
                        .toList())));
    }

    public java.util.Optional<BedrockIdentityClaims> get(ConnectPlayer player) {
        return identities.get(player);
    }

    private static boolean hasPrivateIdentity(Session session) {
        return session != null && session.hasPlayer() && session.getPlayer().hasProfile() &&
                session.getPlayer().getProfile().getPropertiesList().stream().anyMatch(property ->
                        BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()) ||
                                BedrockIdentityProfiles.SCOPE_PROPERTY_NAME.equals(property.getName()));
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Bedrock admission coordinator is closed");
    }

    private static void clear(Admission admission) {
        if (admission != null) {
            admission.raw = null;
            admission.state = AdmissionState.CANCELLED;
            cancel(admission);
        }
    }

    private static void cancel(Admission admission) {
        if (admission.cleanup != null) admission.cleanup.cancel(false);
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

    /** Opaque identity-only handle; it intentionally carries no session/profile/coordinator reference. */
    public static final class AdmissionToken { }

    private static final class Admission {
        private Session raw;
        private final AdmissionToken token;
        private ConnectPlayer player;
        private ScheduledFuture<?> cleanup;
        private AdmissionState state = AdmissionState.PENDING;

        private Admission(Session raw, AdmissionToken token) {
            this.raw = raw;
            this.token = token;
        }
    }

    private enum AdmissionState {
        PENDING,
        VERIFYING,
        CONSUMED,
        CANCELLED
    }
}
