package com.minekube.connect.api.player.principal;

import java.util.Objects;

/** A privacy-safe verification rejection whose only contract is its bounded category. */
public final class PrincipalVerificationException extends Exception {
    private final PrincipalError error;

    public PrincipalVerificationException(PrincipalError error) {
        super(Objects.requireNonNull(error, "error").name(), null, false, false);
        this.error = error;
    }

    public PrincipalError error() {
        return error;
    }
}
