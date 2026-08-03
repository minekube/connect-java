package com.minekube.connect.api.player.principal;

/** Stable, privacy-safe Bedrock principal v2 verification categories. */
public enum PrincipalError {
    MALFORMED,
    TRUST,
    SIGNATURE,
    BINDING_MISMATCH,
    TIME,
    IDENTITY,
    LINK,
    REPLAY,
    CAPACITY,
    METADATA_UNAVAILABLE,
    KEY_REVOKED,
    READINESS,
    INTERNAL
}
