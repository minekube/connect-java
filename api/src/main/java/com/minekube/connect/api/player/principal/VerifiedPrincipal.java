package com.minekube.connect.api.player.principal;

/** A verifier-created principal. Host code cannot implement this sealed hierarchy. */
public sealed interface VerifiedPrincipal permits VerifiedBedrockPrincipal {
    SubjectKind subjectKind();
    EffectiveGameProfile effectiveGameProfile();
}
