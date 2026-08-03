package com.minekube.connect.api.player.principal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class ImmutableVerifiedBedrockPrincipal implements VerifiedBedrockPrincipal {
    private final transient SubjectKind subjectKind;
    private final transient CanonicalXuid xuid;
    private final transient UUID canonicalUnlinkedUuid;
    private final transient VerifiedLinkedJavaIdentity linkedJava;
    private final transient String bedrockDisplayName;
    private final transient VerificationEvidence verification;
    private final transient PrincipalBindings bindings;

    ImmutableVerifiedBedrockPrincipal(
            SubjectKind subjectKind,
            CanonicalXuid xuid,
            UUID canonicalUnlinkedUuid,
            VerifiedLinkedJavaIdentity linkedJava,
            String bedrockDisplayName,
            VerificationEvidence verification,
            PrincipalBindings bindings) {
        this.subjectKind = Objects.requireNonNull(subjectKind, "subjectKind");
        this.xuid = Objects.requireNonNull(xuid, "xuid");
        this.canonicalUnlinkedUuid = Objects.requireNonNull(canonicalUnlinkedUuid, "canonicalUnlinkedUuid");
        this.linkedJava = linkedJava;
        this.bedrockDisplayName = Objects.requireNonNull(bedrockDisplayName, "bedrockDisplayName");
        this.verification = Objects.requireNonNull(verification, "verification");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    @Override public SubjectKind subjectKind() { return subjectKind; }
    @Override public CanonicalXuid xuid() { return xuid; }
    @Override public UUID canonicalUnlinkedUuid() { return canonicalUnlinkedUuid; }
    @Override public Optional<VerifiedLinkedJavaIdentity> linkedJava() { return Optional.ofNullable(linkedJava); }
    @Override public String bedrockDisplayName() { return bedrockDisplayName; }
    @Override public VerificationEvidence verification() { return verification; }
    @Override public PrincipalBindings bindings() { return bindings; }

    @Override
    public EffectiveGameProfile effectiveGameProfile() {
        return linkedJava == null
                ? new EffectiveGameProfile(canonicalUnlinkedUuid, bedrockDisplayName)
                : new EffectiveGameProfile(linkedJava.uuid(), linkedJava.name());
    }

    @Override
    public String toString() {
        return "VerifiedBedrockPrincipal[subjectKind=" + subjectKind + ", linked="
                + (linkedJava != null) + ", verification=" + verification + "]";
    }
}
