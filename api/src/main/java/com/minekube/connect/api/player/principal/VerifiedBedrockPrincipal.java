package com.minekube.connect.api.player.principal;

import java.util.Optional;
import java.util.UUID;

/** A Bedrock principal returned only after complete v2 verification and replay consumption. */
public sealed interface VerifiedBedrockPrincipal extends VerifiedPrincipal
        permits ImmutableVerifiedBedrockPrincipal {
    CanonicalXuid xuid();
    UUID canonicalUnlinkedUuid();
    Optional<VerifiedLinkedJavaIdentity> linkedJava();
    String bedrockDisplayName();
    VerificationEvidence verification();
    PrincipalBindings bindings();
}
