package com.minekube.connect.api.player.principal;

import java.util.Objects;
import java.util.UUID;

/** Immutable Java identity selected only after link provenance verification. */
public final class VerifiedLinkedJavaIdentity {
    private final transient UUID uuid;
    private final transient String name;
    private final transient LinkProvenance provenance;

    public VerifiedLinkedJavaIdentity(UUID uuid, String name, LinkProvenance provenance) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.name = Objects.requireNonNull(name, "name");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public LinkProvenance provenance() { return provenance; }

    @Override
    public String toString() {
        return "VerifiedLinkedJavaIdentity[redacted]";
    }
}
