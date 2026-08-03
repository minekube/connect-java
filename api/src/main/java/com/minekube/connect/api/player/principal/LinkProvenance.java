package com.minekube.connect.api.player.principal;

import java.time.Instant;
import java.util.Objects;

/** Non-credential provenance for an independently verified Java account link. */
public final class LinkProvenance {
    private final transient String provider;
    private final transient String recordId;
    private final long revision;
    private final Instant verifiedAt;

    public LinkProvenance(String provider, String recordId, long revision, Instant verifiedAt) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.recordId = Objects.requireNonNull(recordId, "recordId");
        this.revision = revision;
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
    }

    public String provider() { return provider; }
    public String recordId() { return recordId; }
    public long revision() { return revision; }
    public Instant verifiedAt() { return verifiedAt; }

    @Override
    public String toString() {
        return "LinkProvenance[revision=" + revision + ", verifiedAt=" + verifiedAt + "]";
    }
}
