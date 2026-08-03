package com.minekube.connect.api.player.principal;

import java.util.Objects;

/** The sole public construction entry point for a Bedrock principal verifier. */
public final class BedrockPrincipalVerifierFactory {
    private BedrockPrincipalVerifierFactory() {}

    public static BedrockPrincipalVerifier create(VerifierConfiguration configuration) {
        return new DefaultBedrockPrincipalVerifier(
                Objects.requireNonNull(configuration, "configuration"));
    }
}
