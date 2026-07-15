package com.minekube.connect.bedrock;

import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;

@Singleton
public final class VerifiedBedrockIdentityRegistry {
    private final Map<String, BedrockIdentityClaims> identities = new ConcurrentHashMap<>();

    void record(ConnectPlayer player, BedrockIdentityClaims claims) {
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

    public void remove(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        identities.remove(player.getSessionId());
    }
}
