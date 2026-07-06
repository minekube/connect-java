package com.minekube.connect.bedrock;

import com.google.inject.Inject;
import com.google.rpc.Code;
import com.google.rpc.Status;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import javax.inject.Named;
import okhttp3.OkHttpClient;

public final class BedrockIdentityEnforcer {
    private static final String MODE_DISABLED = "disabled";
    private static final String MODE_WARN = "warn";
    private static final String MODE_REQUIRE = "require";
    private static final String PROTOCOL_BEDROCK = "bedrock";
    private static final String REJECT_MESSAGE = "Bedrock identity verification failed";

    private final ConnectConfig config;
    private final ConnectLogger logger;
    private final Supplier<Instant> now;
    private final BedrockIdentityKeyProvider keyProvider;
    private final BedrockIdentityReplayCache replayCache = new BedrockIdentityReplayCache();

    @Inject
    public BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            @Named("defaultHttpClient") OkHttpClient httpClient) {
        this(config, logger, Instant::now, new BedrockIdentityKeyProvider(config, httpClient));
    }

    BedrockIdentityEnforcer(ConnectConfig config, ConnectLogger logger, Supplier<Instant> now) {
        this(config, logger, now, new BedrockIdentityKeyProvider(config, new OkHttpClient(), now));
    }

    BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            Supplier<Instant> now,
            BedrockIdentityKeyProvider keyProvider) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.now = Objects.requireNonNull(now, "now");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
    }

    public Decision verify(LocalSession.Context context) {
        Objects.requireNonNull(context, "context");
        return verify(context.getPlayer(), context.getEndpointId(), context.getEndpointOrgId());
    }

    public void reject(LocalSession.Context context, Decision decision) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(decision, "decision");
        context.getSessionProposal().reject(Status.newBuilder()
                .setCode(Code.PERMISSION_DENIED_VALUE)
                .setMessage(decision.message())
                .build());
    }

    public Decision verify(ConnectPlayer player) {
        return verify(player, "", "");
    }

    Decision verify(ConnectPlayer player, String endpointId, String endpointOrgId) {
        Objects.requireNonNull(player, "player");
        BedrockIdentityConfig bedrockIdentity = config.getBedrockIdentity();
        String mode = mode(bedrockIdentity);
        if (MODE_DISABLED.equals(mode)) {
            return Decision.allowed(null);
        }

        if (MODE_WARN.equals(mode) && !hasConfiguredKeys(bedrockIdentity)) {
            return Decision.allowed(null);
        }

        try {
            BedrockIdentityClaims claims = verifyWithKeys(player, bedrockIdentity, endpointId, endpointOrgId);
            return Decision.allowed(claims);
        } catch (RuntimeException | BedrockIdentityVerificationException e) {
            warn(player, safeReason(e));
            if (MODE_REQUIRE.equals(mode)) {
                return Decision.rejected(REJECT_MESSAGE);
            }
            return Decision.allowed(null);
        }
    }

    private BedrockIdentityClaims verifyWithKeys(
            ConnectPlayer player,
            BedrockIdentityConfig bedrockIdentity,
            String endpointId,
            String endpointOrgId) throws BedrockIdentityVerificationException {
        List<byte[]> keys = keyProvider.keys();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Bedrock identity public key is not configured");
        }
        BedrockIdentityVerificationException verificationError = null;
        RuntimeException runtimeError = null;
        for (byte[] publicKey : keys) {
            try {
                return verifier(publicKey, player, bedrockIdentity, endpointId, endpointOrgId)
                        .verify(player.getGameProfile());
            } catch (BedrockIdentityVerificationException e) {
                verificationError = e;
            } catch (RuntimeException e) {
                runtimeError = e;
            }
        }
        if (verificationError != null) {
            throw verificationError;
        }
        if (runtimeError != null) {
            throw runtimeError;
        }
        throw new IllegalArgumentException("Bedrock identity public key is not configured");
    }

    private BedrockIdentityVerifier verifier(
            byte[] publicKey,
            ConnectPlayer player,
            BedrockIdentityConfig bedrockIdentity,
            String endpointId,
            String endpointOrgId) {
        BedrockIdentityVerifier.Builder builder = BedrockIdentityVerifier.builder()
                .publicKey(publicKey)
                .now(now)
                .endpointName(config.getEndpoint())
                .sessionId(player.getSessionId())
                .protocol(PROTOCOL_BEDROCK)
                .bedrockAuthPolicy(bedrockIdentity.getExpectedPolicy())
                .replayCache(replayCache);
        if (!isEmpty(endpointId)) {
            builder.endpointId(endpointId);
        }
        if (!isEmpty(endpointOrgId)) {
            builder.orgId(endpointOrgId);
        }
        return builder.build();
    }

    private static String mode(BedrockIdentityConfig bedrockIdentity) {
        if (bedrockIdentity == null || isEmpty(bedrockIdentity.getEnforcement())) {
            return MODE_DISABLED;
        }
        return bedrockIdentity.getEnforcement().toLowerCase(Locale.ROOT);
    }

    private static boolean hasConfiguredKeys(BedrockIdentityConfig bedrockIdentity) {
        if (bedrockIdentity == null) {
            return false;
        }
        if (!isEmpty(bedrockIdentity.getPublicKey()) || !isEmpty(bedrockIdentity.getMetadataUrl())) {
            return true;
        }
        return bedrockIdentity.getPublicKeys() != null && !bedrockIdentity.getPublicKeys().isEmpty();
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
