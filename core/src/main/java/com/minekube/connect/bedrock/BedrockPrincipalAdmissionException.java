package com.minekube.connect.bedrock;

import com.minekube.connect.api.player.principal.PrincipalError;
import java.util.Objects;

/** Privacy-safe boundary exception used before a platform profile is applied. */
public final class BedrockPrincipalAdmissionException extends RuntimeException {
    private final PrincipalError error;

    BedrockPrincipalAdmissionException(PrincipalError error) {
        super(Objects.requireNonNull(error, "error").name(), null, false, false);
        this.error = error;
    }

    public PrincipalError error() {
        return error;
    }
}
