package com.minekube.connect.api.player.principal;

/** Strict Bedrock signed-principal v2 verifier. */
public interface BedrockPrincipalVerifier {
    VerifiedBedrockPrincipal verifyAndConsume(
            SignedPrincipalEnvelope envelope,
            TrustedProposalContext expected) throws PrincipalVerificationException;
}
