package com.minekube.connect.bedrock;

import com.google.inject.Inject;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.api.player.GameProfile;
import com.minekube.connect.api.player.bedrock.BedrockIdentityClaims;
import com.minekube.connect.api.player.bedrock.BedrockIdentityReplayCache;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerificationException;
import com.minekube.connect.api.player.bedrock.BedrockIdentityVerifier;
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.config.ConnectConfig.BedrockIdentityConfig;
import com.minekube.connect.network.netty.LocalSession;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.inject.Named;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import okhttp3.OkHttpClient;

public final class BedrockIdentityEnforcer {
    private static final String MODE_DISABLED = "disabled";
    private static final String MODE_WARN = "warn";
    private static final String MODE_REQUIRE = "require";
    private static final String PROTOCOL_BEDROCK = "bedrock";
    private static final String REJECT_MESSAGE = "Bedrock identity verification failed";

    private final Supplier<ConnectConfig> config;
    private final ConnectLogger logger;
    private final Supplier<Instant> now;
    private final BedrockIdentityKeyProvider keyProvider;
    private final BedrockAdmissionCoordinator admissionCoordinator;
    private final BedrockIdentityReplayCache replayCache = new BedrockIdentityReplayCache();

    /**
     * Injection seam used by the plugin. The config is resolved lazily from {@link ConfigHolder} so
     * this enforcer can be constructed by the (config-agnostic) parent injector while the platform
     * injector is built, before {@code ConnectPlatform.init()} loads the configuration.
     */
    @Inject
    public BedrockIdentityEnforcer(
            ConfigHolder configHolder,
            ConnectLogger logger,
            BedrockIdentityKeyProvider keyProvider,
            BedrockAdmissionCoordinator admissionCoordinator) {
        this(Objects.requireNonNull(configHolder, "configHolder")::get, logger, Instant::now,
                keyProvider, admissionCoordinator);
    }

    public BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            @Named("defaultHttpClient") OkHttpClient httpClient) {
        this(config, logger, Instant::now, new BedrockIdentityKeyProvider(config, httpClient),
                (BedrockAdmissionCoordinator) null);
    }

    BedrockIdentityEnforcer(ConnectConfig config, ConnectLogger logger, Supplier<Instant> now) {
        this(config, logger, now, new BedrockIdentityKeyProvider(config, new okhttp3.OkHttpClient(), now),
                (BedrockAdmissionCoordinator) null);
    }

    BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            Supplier<Instant> now,
            VerifiedBedrockIdentityRegistry ignored) {
        this(config, logger, now, new BedrockIdentityKeyProvider(config, new okhttp3.OkHttpClient(), now),
                (BedrockAdmissionCoordinator) null);
    }

    BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            Supplier<Instant> now,
            BedrockAdmissionCoordinator admissionCoordinator) {
        this(config, logger, now, new BedrockIdentityKeyProvider(config, new okhttp3.OkHttpClient(), now),
                admissionCoordinator);
    }

    BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            Supplier<Instant> now,
            BedrockIdentityKeyProvider keyProvider) {
        this(config, logger, now, keyProvider, (BedrockAdmissionCoordinator) null);
    }

    BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            Supplier<Instant> now,
            BedrockIdentityKeyProvider keyProvider,
            BedrockAdmissionCoordinator admissionCoordinator) {
        this(constant(config), logger, now, keyProvider, admissionCoordinator);
    }

    private BedrockIdentityEnforcer(
            Supplier<ConnectConfig> config,
            ConnectLogger logger,
            Supplier<Instant> now,
            BedrockIdentityKeyProvider keyProvider,
            BedrockAdmissionCoordinator admissionCoordinator) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.now = Objects.requireNonNull(now, "now");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.admissionCoordinator = admissionCoordinator;
    }

    private static Supplier<ConnectConfig> constant(ConnectConfig config) {
        Objects.requireNonNull(config, "config");
        return () -> config;
    }

    private ConnectConfig config() {
        return Objects.requireNonNull(config.get(), "config");
    }

    BedrockIdentityEnforcer(
            ConnectConfig config,
            ConnectLogger logger,
            Supplier<Instant> now,
            BedrockIdentityKeyProvider keyProvider,
            VerifiedBedrockIdentityRegistry ignored) {
        this(config, logger, now, keyProvider, (BedrockAdmissionCoordinator) null);
    }

    public Decision verify(LocalSession.Context context) {
        Objects.requireNonNull(context, "context");
        ConnectPlayer player = context.getPlayer();
        Decision stagedDecision = admissionCoordinator == null ? null : admissionCoordinator.verify(
                player, context.getAdmissionToken(), this, context.getEndpointId(),
                context.getEndpointOrgId(), context.getProtocol());
        if (stagedDecision != null) {
            return stagedDecision;
        }
        return verifyAdmission(
                player,
                context.getEndpointId(),
                context.getEndpointOrgId(),
                context.getProtocol());
    }

    Decision verifyAdmission(
            ConnectPlayer player,
            String endpointId,
            String endpointOrgId,
            SessionProtocol protocol) {
        return verifyAdmissionSnapshot(player, player.getGameProfile(), endpointId, endpointOrgId, protocol);
    }

    Decision verifyAdmissionSnapshot(
            ConnectPlayer player,
            GameProfile profile,
            String endpointId,
            String endpointOrgId,
            SessionProtocol protocol) {
        return verify(player, profile, endpointId, endpointOrgId, protocol);
    }

    Decision invalidatedAdmission() {
        return Decision.rejected(REJECT_MESSAGE);
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
        return verify(player, player.getGameProfile(), "", "", SessionProtocol.SESSION_PROTOCOL_BEDROCK);
    }

    Decision verify(ConnectPlayer player, String endpointId, String endpointOrgId) {
        return verify(
                player,
                player.getGameProfile(),
                endpointId,
                endpointOrgId,
                SessionProtocol.SESSION_PROTOCOL_BEDROCK);
    }

    Decision verify(
            ConnectPlayer player,
            String endpointId,
            String endpointOrgId,
            SessionProtocol protocol) {
        return verify(player, player.getGameProfile(), endpointId, endpointOrgId, protocol);
    }

    Decision verify(
            ConnectPlayer player,
            GameProfile profile,
            String endpointId,
            String endpointOrgId,
            SessionProtocol protocol) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(protocol, "protocol");
        BedrockIdentityConfig bedrockIdentity = config().getBedrockIdentity();
        BedrockIdentityConfiguration identityConfiguration = BedrockIdentityConfiguration.from(bedrockIdentity);
        if (identityConfiguration.isDisabled()) {
            return Decision.allowed(null);
        }

        String mode = identityConfiguration.mode() == BedrockIdentityConfiguration.Mode.WARN ? MODE_WARN : MODE_REQUIRE;

        boolean hasEnvelope = hasReservedEnvelope(profile);
        if (protocol == SessionProtocol.SESSION_PROTOCOL_JAVA) {
            if (!hasEnvelope) {
                return Decision.allowed(null);
            }
            return rejectOrWarn(player, mode, "reserved identity property on Java session");
        }
        if (protocol != SessionProtocol.SESSION_PROTOCOL_BEDROCK &&
                protocol != SessionProtocol.SESSION_PROTOCOL_UNSPECIFIED) {
            if (!hasEnvelope) {
                return Decision.allowed(null);
            }
            return rejectOrWarn(player, mode, "reserved identity property on unknown session protocol");
        }
        if (protocol == SessionProtocol.SESSION_PROTOCOL_UNSPECIFIED && !hasEnvelope) {
            return Decision.allowed(null);
        }

        if (hasEnvelope && (!hasScopeValue(endpointId) || !hasScopeValue(endpointOrgId))) {
            return rejectOrWarn(player, mode, "authenticated endpoint scope is incomplete");
        }
        if (!identityConfiguration.isUsable()) {
            return Decision.rejected(REJECT_MESSAGE);
        }

        if (MODE_WARN.equals(mode) && !hasConfiguredKeys(bedrockIdentity)) {
            return Decision.allowed(null);
        }

        try {
            BedrockIdentityClaims claims = verifyWithKeys(
                    player,
                    profile,
                    identityConfiguration,
                    endpointId,
                    endpointOrgId);
            return Decision.allowed(claims);
        } catch (RuntimeException | BedrockIdentityVerificationException e) {
            warn(player, safeReason(e));
            if (MODE_REQUIRE.equals(mode)) {
                return Decision.rejected(REJECT_MESSAGE);
            }
            return Decision.allowed(null);
        }
    }

    private Decision rejectOrWarn(ConnectPlayer player, String mode, String reason) {
        warn(player, reason);
        return MODE_REQUIRE.equals(mode) ? Decision.rejected(REJECT_MESSAGE) : Decision.allowed(null);
    }

    private BedrockIdentityClaims verifyWithKeys(
            ConnectPlayer player,
            GameProfile profile,
            BedrockIdentityConfiguration identityConfiguration,
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
                return verifier(publicKey, player, identityConfiguration, endpointId, endpointOrgId)
                        .verify(profile);
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
            BedrockIdentityConfiguration identityConfiguration,
            String endpointId,
            String endpointOrgId) {
        BedrockIdentityVerifier.Builder builder = BedrockIdentityVerifier.builder()
                .publicKey(publicKey)
                .now(now)
                .endpointId(endpointId)
                .endpointName(config().getEndpoint())
                .orgId(endpointOrgId)
                .sessionId(player.getSessionId())
                .protocol(PROTOCOL_BEDROCK)
                .bedrockAuthPolicy(identityConfiguration.policy())
                .expectedIssuer(identityConfiguration.issuer())
                .replayCache(replayCache);
        return builder.build();
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

    private static boolean hasScopeValue(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean hasReservedEnvelope(GameProfile profile) {
        return profile.getProperties().stream()
                .anyMatch(property -> BedrockIdentityVerifier.PROPERTY_NAME.equals(property.getName()));
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
