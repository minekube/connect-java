package com.minekube.connect.api.player.principal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Opaque, bounded compact-JWS input. */
public final class SignedPrincipalEnvelope {
    public static final int MAX_COMPACT_BYTES = 16 * 1024;
    private final transient String compact;

    private SignedPrincipalEnvelope(String compact) {
        this.compact = compact;
    }

    public static SignedPrincipalEnvelope of(String compact) {
        Objects.requireNonNull(compact, "compact");
        int size = compact.getBytes(StandardCharsets.UTF_8).length;
        if (size == 0 || size > MAX_COMPACT_BYTES) {
            throw new IllegalArgumentException(PrincipalError.MALFORMED.name());
        }
        return new SignedPrincipalEnvelope(compact);
    }

    String compact() {
        return compact;
    }

    @Override
    public String toString() {
        return "SignedPrincipalEnvelope[redacted]";
    }
}
