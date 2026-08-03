package com.minekube.connect.api.player.principal;

import java.util.Objects;

/** A canonical positive decimal XUID verified by the SDK. */
public final class CanonicalXuid {
    private final transient String value;

    CanonicalXuid(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanonicalXuid && value.equals(((CanonicalXuid) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "CanonicalXuid[redacted]";
    }
}
