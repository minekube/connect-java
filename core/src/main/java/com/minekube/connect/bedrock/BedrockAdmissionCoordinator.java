package com.minekube.connect.bedrock;

import com.google.rpc.Status;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.player.ConnectPlayerImpl;
import com.minekube.connect.watch.SessionProposal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
    private final Map<AdmissionToken, Admission> admissions = new HashMap<>();
    private final Map<String, Admission> latestBySession = new HashMap<>();
    private final Map<ConnectPlayer, AdmissionToken> players = new IdentityHashMap<>();
    private long nextGeneration;
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
            Consumer<Status> reject,
            String endpointId,
            String endpointOrgId) {
        ensureOpen();
        String sessionId = raw == null ? "" : raw.getId();
        removeAdmission(latestBySession.get(sessionId));
        AdmissionToken token = new AdmissionToken(++nextGeneration);
        Admission admission = new Admission(raw, token, sessionId);
        admissions.put(token, admission);
        latestBySession.put(sessionId, admission);
        try {
            admission.cleanup = cleanupExecutor.schedule(
                    () -> expire(token, admission),
                    ADMISSION_TTL_SECONDS,
                    TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            removeAdmission(admission);
            throw e;
        }
        return new SessionProposal(raw, reject, endpointId, endpointOrgId, token);
    }

    public synchronized ConnectPlayer stage(SessionProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        AdmissionToken token = proposal.getAdmissionToken();
        if (token == null) {
            return publicPlayer(playerFor(proposal.getSession()));
        }
        Admission admission = admissions.get(token);
        if (closed || admission == null || latestBySession.get(admission.sessionId) != admission ||
                admission.raw == null || admission.player != null ||
                admission.state != AdmissionState.PENDING) {
            throw new IllegalStateException("Bedrock admission has expired or been superseded");
        }
        ConnectPlayer publicPlayer = publicPlayer(playerFor(admission.raw));
        admission.player = publicPlayer;
        players.put(publicPlayer, token);
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
                    latestBySession.get(admission.sessionId) != admission ||
                    admission.raw == null || admission.state != AdmissionState.PENDING) {
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
                removeAdmission(admission);
            }
            throw e;
        }
        synchronized (this) {
            if (closed || admissions.get(token) != admission ||
                    latestBySession.get(admission.sessionId) != admission ||
                    admission.state != AdmissionState.VERIFYING) {
                return enforcer.invalidatedAdmission();
            }
            admissions.remove(token, admission);
            latestBySession.remove(admission.sessionId, admission);
            players.remove(player);
            cancel(admission);
            admission.state = AdmissionState.CONSUMED;
            if (decision.verifiedClaims() != null) {
                identities.record(player, token.generation, decision.verifiedClaims());
            }
            return decision;
        }
    }

    public synchronized void discard(SessionProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        AdmissionToken token = proposal.getAdmissionToken();
        if (token != null) {
            removeAdmission(admissions.get(token));
        }
    }

    public void reject(SessionProposal proposal, Status reason) {
        discard(proposal);
        proposal.reject(reason);
    }

    public synchronized void discard(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        AdmissionToken token = players.get(player);
        if (token != null) {
            removeAdmission(admissions.get(token));
        }
        identities.remove(player);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        new ArrayList<>(admissions.values()).forEach(this::removeAdmission);
        admissions.clear();
        latestBySession.clear();
        players.clear();
        identities.close();
        cleanupExecutor.shutdownNow();
    }

    private synchronized void expire(AdmissionToken token, Admission expected) {
        if (admissions.get(token) == expected) {
            removeAdmission(expected);
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
                        .map(property -> new GameProfile.Property(
                                property.getName(),
                                property.getValue(),
                                property.getSignature()))
                        .toList())));
    }

    private static ConnectPlayer publicPlayer(ConnectPlayer player) {
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Bedrock admission coordinator is closed");
        }
    }

    private void removeAdmission(Admission admission) {
        if (admission == null) {
            return;
        }
        admissions.remove(admission.token, admission);
        latestBySession.remove(admission.sessionId, admission);
        if (admission.player != null) {
            players.remove(admission.player);
            identities.remove(admission.player);
        }
        clear(admission);
    }

    private static void clear(Admission admission) {
        admission.raw = null;
        admission.state = AdmissionState.CANCELLED;
        cancel(admission);
    }

    private static void cancel(Admission admission) {
        if (admission.cleanup != null) {
            admission.cleanup.cancel(false);
        }
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
    public static final class AdmissionToken {
        private final long generation;

        private AdmissionToken(long generation) {
            this.generation = generation;
        }
    }

    private static final class Admission {
        private Session raw;
        private final AdmissionToken token;
        private final String sessionId;
        private ConnectPlayer player;
        private ScheduledFuture<?> cleanup;
        private AdmissionState state = AdmissionState.PENDING;

        private Admission(Session raw, AdmissionToken token, String sessionId) {
            this.raw = raw;
            this.token = token;
            this.sessionId = sessionId;
        }
    }

    private enum AdmissionState {
        PENDING,
        VERIFYING,
        CONSUMED,
        CANCELLED
    }
}
