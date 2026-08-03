package com.minekube.connect.bedrock;

import com.google.inject.Singleton;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.api.player.principal.VerifiedBedrockPrincipal;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Singleton
public final class VerifiedBedrockIdentityRegistry implements AutoCloseable {
    private final Map<ConnectPlayer, VerifiedIdentity> identities = new IdentityHashMap<>();
    private boolean closed;

    synchronized void record(ConnectPlayer player, BedrockIdentityClaims claims) {
        record(player, 0, claims);
    }

    synchronized void record(
            ConnectPlayer player,
            long generation,
            BedrockIdentityClaims claims) {
        ensureOpen();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(claims, "claims");
        if (!player.getSessionId().equals(claims.getSessionId())) {
            throw new IllegalArgumentException("Bedrock identity claims session mismatch");
        }
        VerifiedIdentity current = identities.get(player);
        if (current == null || current.generation <= generation) {
            identities.put(player, new VerifiedIdentity(
                    generation, player.getSessionId(), claims));
        }
    }

    synchronized void recordPrincipal(
            ConnectPlayer player,
            long generation,
            VerifiedBedrockPrincipal principal) {
        ensureOpen();
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(principal, "principal");
        if (!player.getSessionId().equals(principal.bindings().connectSessionId())) {
            throw new IllegalArgumentException("Bedrock principal session mismatch");
        }
        VerifiedIdentity current = identities.get(player);
        if (current == null || current.generation <= generation) {
            identities.put(player, new VerifiedIdentity(
                    generation, player.getSessionId(), null, principal));
        }
    }

    public synchronized Optional<BedrockIdentityClaims> get(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        VerifiedIdentity identity = identities.get(player);
        if (identity == null || !player.getSessionId().equals(identity.sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(identity.claims);
    }

    public synchronized Optional<VerifiedBedrockPrincipal> getPrincipal(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        VerifiedIdentity identity = identities.get(player);
        if (identity == null || !player.getSessionId().equals(identity.sessionId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(identity.principal);
    }

    public synchronized void remove(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        identities.remove(player);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        identities.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Bedrock identity registry is closed");
        }
    }

    private static final class VerifiedIdentity {
        private final long generation;
        private final String sessionId;
        private final BedrockIdentityClaims claims;
        private final VerifiedBedrockPrincipal principal;

        private VerifiedIdentity(
                long generation,
                String sessionId,
                BedrockIdentityClaims claims) {
            this(generation, sessionId, claims, null);
        }

        private VerifiedIdentity(
                long generation,
                String sessionId,
                BedrockIdentityClaims claims,
                VerifiedBedrockPrincipal principal) {
            this.generation = generation;
            this.sessionId = sessionId;
            this.claims = claims;
            this.principal = principal;
        }
    }
}
