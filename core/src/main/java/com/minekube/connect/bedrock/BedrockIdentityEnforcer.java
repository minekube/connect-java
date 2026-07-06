package com.minekube.connect.bedrock;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.api.player.bedrock.BedrockIdentityReplayCache;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerificationException;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.config.ConnectConfig.BedrockIdentityConfig;
import com.minekube.connect.network.netty.LocalSession;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class BedrockIdentityEnforcer {
    private static final String MODE_DISABLED = "disabled";
    private static final String MODE_WARN = "warn";
    private static final String MODE_REQUIRE = "require";
    private static final String PROTOCOL_BEDROCK = "bedrock";
    private static final String REJECT_MESSAGE = "Bedrock identity verification failed";

    private final ConnectConfig config;
    private final ConnectLogger logger;
    private final Supplier<Instant> now;
    private final BedrockIdentityReplayCache replayCache = new BedrockIdentityReplayCache();

    public BedrockIdentityEnforcer(ConnectConfig config, ConnectLogger logger) {
        this(config, logger, Instant::now);
    }

    BedrockIdentityEnforcer(ConnectConfig config, ConnectLogger logger, Supplier<Instant> now) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.now = Objects.requireNonNull(now, "now");
    }

    public Decision verify(LocalSession.Context context) {
        Objects.requireNonNull(context, "context");
        return verify(context.getPlayer());
    }

    public Decision verify(ConnectPlayer player) {
        Objects.requireNonNull(player, "player");
        BedrockIdentityConfig bedrockIdentity = config.getBedrockIdentity();
        String mode = mode(bedrockIdentity);
        if (MODE_DISABLED.equals(mode)) {
            return Decision.allowed(null);
        }

        if (MODE_WARN.equals(mode) && isEmpty(bedrockIdentity.getPublicKey())) {
            return Decision.allowed(null);
        }

        try {
            BedrockIdentityClaims claims = verifier(player, bedrockIdentity).verify(player.getGameProfile());
            return Decision.allowed(claims);
        } catch (RuntimeException | BedrockIdentityVerificationException e) {
            warn(player, safeReason(e));
            if (MODE_REQUIRE.equals(mode)) {
                return Decision.rejected(REJECT_MESSAGE);
            }
            return Decision.allowed(null);
        }
    }

    private BedrockIdentityVerifier verifier(ConnectPlayer player, BedrockIdentityConfig bedrockIdentity) {
        byte[] publicKey = Base64.getDecoder().decode(requirePublicKey(bedrockIdentity));
        return BedrockIdentityVerifier.builder()
                .publicKey(publicKey)
                .now(now)
                .endpointName(config.getEndpoint())
                .sessionId(player.getSessionId())
                .protocol(PROTOCOL_BEDROCK)
                .bedrockAuthPolicy(bedrockIdentity.getExpectedPolicy())
                .replayCache(replayCache)
                .build();
    }

    private static String mode(BedrockIdentityConfig bedrockIdentity) {
        if (bedrockIdentity == null || isEmpty(bedrockIdentity.getEnforcement())) {
            return MODE_DISABLED;
        }
        return bedrockIdentity.getEnforcement().toLowerCase(Locale.ROOT);
    }

    private static String requirePublicKey(BedrockIdentityConfig bedrockIdentity) {
        if (bedrockIdentity == null || isEmpty(bedrockIdentity.getPublicKey())) {
            throw new IllegalArgumentException("Bedrock identity public key is not configured");
        }
        return bedrockIdentity.getPublicKey();
    }

    private void warn(ConnectPlayer player, String reason) {
        logger.warn("Bedrock identity verification failed for player={} session={}: {}",
                player.getUsername(), player.getSessionId(), reason);
    }

    private static String safeReason(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid public key";
        }
        if (error instanceof BedrockIdentityVerificationException) {
            return error.getMessage() == null ? "invalid identity envelope" : error.getMessage();
        }
        return error.getClass().getSimpleName();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public static final class Decision {
        private final boolean allowed;
        private final String message;
        private final BedrockIdentityClaims verifiedClaims;

        private Decision(boolean allowed, String message, BedrockIdentityClaims verifiedClaims) {
            this.allowed = allowed;
            this.message = message;
            this.verifiedClaims = verifiedClaims;
        }

        public static Decision allowed(BedrockIdentityClaims verifiedClaims) {
            return new Decision(true, "", verifiedClaims);
        }

        public static Decision rejected(String message) {
            return new Decision(false, message, null);
        }

        public boolean allowed() {
            return allowed;
        }

        public String message() {
            return message;
        }

        public BedrockIdentityClaims verifiedClaims() {
            return verifiedClaims;
        }
    }
}
