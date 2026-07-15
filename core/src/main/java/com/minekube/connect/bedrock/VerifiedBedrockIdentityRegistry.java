package com.minekube.connect.bedrock;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Singleton;

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

    public synchronized Optional<BedrockIdentityClaims> get(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        VerifiedIdentity identity = identities.get(player);
        if (identity == null || !player.getSessionId().equals(identity.sessionId)) {
            return Optional.empty();
        }
        return Optional.of(identity.claims);
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

        private VerifiedIdentity(
                long generation,
                String sessionId,
                BedrockIdentityClaims claims) {
            this.generation = generation;
            this.sessionId = sessionId;
            this.claims = claims;
        }
    }
}
