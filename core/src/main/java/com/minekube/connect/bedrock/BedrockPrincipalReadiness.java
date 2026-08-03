package com.minekube.connect.bedrock;

import com.google.protobuf.ByteString;
import com.minekube.connect.config.ConnectConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import minekube.connect.v1alpha1.WatchServiceOuterClass.PrincipalError;
import minekube.connect.v1alpha1.WatchServiceOuterClass.ReadinessAttestation;
import minekube.connect.v1alpha1.WatchServiceOuterClass.ReadinessChallenge;
import minekube.connect.v1alpha1.WatchServiceOuterClass.TunnelTransport;

/** Honest generation-2 capability and challenge answers for the signed-principal consumer. */
public final class BedrockPrincipalReadiness {
    public static final String CAPABILITY = "bedrock-verified-principal-v2";
    private static final String MODE = "require";
    private static final String CORE_VECTOR_SHA256 =
            "4f2a442ee71bfd35af2ef1f3944489d17551aa77fed2c08220f2aa77032b6196";

    public enum Transport {
        WATCH(TunnelTransport.Type.TYPE_WEBSOCKET),
        LIBP2P(TunnelTransport.Type.TYPE_LIBP2P);

        private final TunnelTransport.Type wireType;

        Transport(TunnelTransport.Type wireType) {
            this.wireType = wireType;
        }
    }

    private final ConnectConfig config;
    private final Clock clock;

    public BedrockPrincipalReadiness(ConnectConfig config) {
        this(config, Clock.systemUTC());
    }

    BedrockPrincipalReadiness(ConnectConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isReady() {
        return BedrockPrincipalConfiguration.from(config.getBedrockPrincipal()).isCapable()
                && usablePins(config.getBedrockPrincipal().getPublicKeys());
    }

    public byte[] revision() {
        if (!isReady()) return new byte[0];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ConnectConfig.BedrockPrincipalConfig principal = config.getBedrockPrincipal();
            update(digest, Integer.toString(principal.getConfigGeneration()));
            update(digest, principal.getMode());
            update(digest, principal.getIssuer());
            update(digest, principal.getTrustDomain());
            update(digest, principal.getAudience());
            update(digest, principal.getMetadataOrigin());
            update(digest, principal.getMetadataPath());
            update(digest, CORE_VECTOR_SHA256);
            principal.getPublicKeys().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .forEach(entry -> {
                        update(digest, entry.getKey());
                        update(digest, entry.getValue());
                    });
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public List<String> capabilities(List<String> configuredCapabilities, Transport transport) {
        Objects.requireNonNull(transport, "transport");
        List<String> capabilities = new ArrayList<>(configuredCapabilities);
        capabilities.removeIf(CAPABILITY::equals);
        if (isReady()) capabilities.add(CAPABILITY);
        return List.copyOf(capabilities);
    }

    public ReadinessAttestation attest(ReadinessChallenge challenge, Transport transport) {
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(transport, "transport");
        long now = clock.instant().getEpochSecond();
        boolean valid = validChallenge(challenge, transport, now);
        boolean ready = valid && isReady();
        ReadinessAttestation.Builder answer = ReadinessAttestation.newBuilder()
                .setChallenge(challenge)
                .setCapability(CAPABILITY)
                .setMode(MODE)
                .setObservedAtUnix(now);
        byte[] revision = revision();
        if (revision.length == 32) answer.setReadinessRevision(ByteString.copyFrom(revision));
        if (ready) {
            return answer.setResult(ReadinessAttestation.Result.RESULT_READY).build();
        }
        return answer.setResult(ReadinessAttestation.Result.RESULT_NOT_READY)
                .setReason(PrincipalError.PRINCIPAL_ERROR_READINESS)
                .build();
    }

    private static boolean validChallenge(ReadinessChallenge challenge, Transport transport, long now) {
        return !challenge.getRequestId().isEmpty()
                && challenge.getNonce().size() == 16
                && !challenge.getEndpointId().isEmpty()
                && !challenge.getOrganizationId().isEmpty()
                && !challenge.getConnectorInstanceId().isEmpty()
                && !challenge.getLeaseId().isEmpty()
                && challenge.getTransport() == transport.wireType
                && challenge.getPolicyRevision() > 0
                && challenge.getExpiresAtUnix() - challenge.getIssuedAtUnix() == 30
                && now >= challenge.getIssuedAtUnix()
                && now <= challenge.getExpiresAtUnix();
    }

    private static boolean usablePins(Map<String, String> pins) {
        if (pins == null || pins.isEmpty()) return false;
        try {
            for (Map.Entry<String, String> pin : pins.entrySet()) {
                if (pin.getKey() == null || pin.getKey().isEmpty() || pin.getKey().length() > 128
                        || pin.getValue() == null) return false;
                byte[] decoded = Base64.getUrlDecoder().decode(pin.getValue());
                if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(decoded).equals(pin.getValue())) return false;
            }
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
