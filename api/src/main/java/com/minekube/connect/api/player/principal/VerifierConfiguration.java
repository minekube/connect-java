package com.minekube.connect.api.player.principal;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable verifier dependencies. Static keys are keyed by the frozen non-secret JWS kid. */
public final class VerifierConfiguration {
    private final Map<String, byte[]> publicKeys;
    private final Clock clock;
    private final int replayCapacity;

    private VerifierConfiguration(Builder builder) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        builder.publicKeys.forEach((kid, key) -> keys.put(kid, key.clone()));
        this.publicKeys = Collections.unmodifiableMap(keys);
        this.clock = builder.clock;
        this.replayCapacity = builder.replayCapacity;
    }

    public static Builder builder() {
        return new Builder();
    }

    Map<String, byte[]> publicKeys() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        publicKeys.forEach((kid, key) -> copy.put(kid, key.clone()));
        return copy;
    }

    Clock clock() { return clock; }
    int replayCapacity() { return replayCapacity; }

    public static final class Builder {
        private final Map<String, byte[]> publicKeys = new LinkedHashMap<>();
        private Clock clock = Clock.systemUTC();
        private int replayCapacity = 65_536;

        public Builder publicKey(String kid, byte[] rawEd25519PublicKey) {
            Objects.requireNonNull(kid, "kid");
            Objects.requireNonNull(rawEd25519PublicKey, "rawEd25519PublicKey");
            if (kid.isEmpty() || kid.length() > 128 || rawEd25519PublicKey.length != 32) {
                throw new IllegalArgumentException("invalid verifier public key");
            }
            publicKeys.put(kid, rawEd25519PublicKey.clone());
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder replayCapacity(int replayCapacity) {
            if (replayCapacity <= 0 || replayCapacity > 65_536) {
                throw new IllegalArgumentException("replayCapacity must be between 1 and 65536");
            }
            this.replayCapacity = replayCapacity;
            return this;
        }

        public VerifierConfiguration build() {
            return new VerifierConfiguration(this);
        }
    }
}
