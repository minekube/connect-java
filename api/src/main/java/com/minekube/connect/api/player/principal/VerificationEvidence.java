package com.minekube.connect.api.player.principal;

import java.time.Instant;
import java.util.Objects;

/** Bounded, non-secret evidence about a successful verification. */
public record VerificationEvidence(
        String kid,
        String verificationMethod,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt) {
    public VerificationEvidence {
        Objects.requireNonNull(kid, "kid");
        Objects.requireNonNull(verificationMethod, "verificationMethod");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
