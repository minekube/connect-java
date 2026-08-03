package com.minekube.connect.bedrock;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.minekube.connect.api.player.bedrock.BedrockIdentityProfiles;
import com.minekube.connect.api.player.principal.BedrockPrincipalVerifier;
import com.minekube.connect.api.player.principal.BedrockPrincipalVerifierFactory;
import com.minekube.connect.api.player.principal.PrincipalError;
import com.minekube.connect.api.player.principal.PrincipalVerificationException;
import com.minekube.connect.api.player.principal.SignedPrincipalEnvelope;
import com.minekube.connect.api.player.principal.TrustedProposalContext;
import com.minekube.connect.api.player.principal.VerifiedBedrockPrincipal;
import com.minekube.connect.api.player.principal.VerifierConfiguration;
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.config.ConnectConfig;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;

/** Consumes the frozen opaque Watch/libp2p v2 fields before host profile application. */
@Singleton
public final class BedrockPrincipalConsumer {
    private final Supplier<ConnectConfig> config;
    private final Clock clock;
    private BedrockPrincipalVerifier verifier;

    @Inject
    public BedrockPrincipalConsumer(ConfigHolder configHolder) {
        this(Objects.requireNonNull(configHolder, "configHolder")::get, Clock.systemUTC());
    }

    BedrockPrincipalConsumer(ConnectConfig config, Clock clock) {
        this(() -> Objects.requireNonNull(config, "config"), clock);
    }

    private BedrockPrincipalConsumer(Supplier<ConnectConfig> config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<VerifiedBedrockPrincipal> verify(Session session) {
        Objects.requireNonNull(session, "session");
        if (hasInjectedProperty(session)) {
            throw new BedrockPrincipalAdmissionException(PrincipalError.BINDING_MISMATCH);
        }
        if (session.getSignedBedrockPrincipalV2().isEmpty()) {
            return Optional.empty();
        }
        if (!BedrockPrincipalConfiguration.from(config().getBedrockPrincipal()).isCapable()) {
            throw new BedrockPrincipalAdmissionException(PrincipalError.READINESS);
        }
        if (session.getProtocol() != SessionProtocol.SESSION_PROTOCOL_BEDROCK
                || session.getConnectSessionNonce().size() != 16
                || session.getSourceProtocolVersion() < 1
                || session.getPolicyRevision() <= 0
                || session.getEndpointId().isEmpty()
                || session.getOrganizationId().isEmpty()
                || session.getId().isEmpty()) {
            throw new BedrockPrincipalAdmissionException(PrincipalError.BINDING_MISMATCH);
        }
        ConnectConfig.BedrockPrincipalConfig principalConfig = config().getBedrockPrincipal();
        TrustedProposalContext expected = new TrustedProposalContext(
                principalConfig.getIssuer(), principalConfig.getTrustDomain(), principalConfig.getAudience(),
                session.getEndpointId(), session.getOrganizationId(), session.getId(),
                session.getConnectSessionNonce().toByteArray(), "bedrock",
                session.getSourceProtocolVersion(), session.getPolicyRevision());
        try {
            return Optional.of(verifier().verifyAndConsume(
                    SignedPrincipalEnvelope.of(strictUtf8(session.getSignedBedrockPrincipalV2().toByteArray())),
                    expected));
        } catch (PrincipalVerificationException error) {
            throw new BedrockPrincipalAdmissionException(error.error());
        } catch (IllegalArgumentException | CharacterCodingException ignored) {
            throw new BedrockPrincipalAdmissionException(PrincipalError.MALFORMED);
        }
    }

    private synchronized BedrockPrincipalVerifier verifier() {
        if (verifier != null) return verifier;
        ConnectConfig.BedrockPrincipalConfig principalConfig = config().getBedrockPrincipal();
        VerifierConfiguration.Builder configuration = VerifierConfiguration.builder().clock(clock);
        Map<String, String> pins = principalConfig.getPublicKeys();
        if (pins == null || pins.isEmpty()) {
            throw new BedrockPrincipalAdmissionException(PrincipalError.METADATA_UNAVAILABLE);
        }
        try {
            pins.forEach((kid, encoded) -> {
                byte[] decoded = Base64.getUrlDecoder().decode(encoded);
                if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
                    throw new IllegalArgumentException();
                }
                configuration.publicKey(kid, decoded);
            });
            verifier = BedrockPrincipalVerifierFactory.create(configuration.build());
            return verifier;
        } catch (IllegalArgumentException ignored) {
            throw new BedrockPrincipalAdmissionException(PrincipalError.TRUST);
        }
    }

    private ConnectConfig config() {
        return Objects.requireNonNull(config.get(), "config");
    }

    private static boolean hasInjectedProperty(Session session) {
        return session.hasPlayer() && session.getPlayer().hasProfile()
                && session.getPlayer().getProfile().getPropertiesList().stream()
                .anyMatch(property -> BedrockIdentityProfiles.PRINCIPAL_V2_PROPERTY_NAME
                        .equals(property.getName()));
    }

    private static String strictUtf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
    }
}
