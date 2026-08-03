package com.minekube.connect.api.player.principal;

import java.util.Objects;
import java.util.UUID;

/** The single game profile selected by a verified principal. */
public record EffectiveGameProfile(UUID uuid, String name) {
    public EffectiveGameProfile {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
