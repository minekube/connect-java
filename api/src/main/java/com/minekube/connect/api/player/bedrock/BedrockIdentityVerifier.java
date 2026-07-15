package com.minekube.connect.api.player.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.minekube.connect.api.player.GameProfile;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.UUID;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class BedrockIdentityVerifier {
    public static final String PROPERTY_NAME = "minekube:bedrock_identity";

    private static final int VERSION = 1;
    private static final String POLICY_LINKED_JAVA_ONLY = "linked_java_only";
    private static final String POLICY_TRUSTED_BEDROCK_XUID = "trusted_bedrock_xuid";
    private static final String PRINCIPAL_BEDROCK_XUID = "bedrock_xuid";
    private static final String PRINCIPAL_BEDROCK_LINKED_JAVA = "bedrock_linked_java";
    private static final Gson GSON = new Gson();
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "version", "issuer", "endpoint", "session", "policy", "principal", "signature");
    private static final Set<String> ENDPOINT_FIELDS = Set.of("id", "name", "org_id");
    private static final Set<String> SESSION_FIELDS = Set.of(
            "id", "protocol", "issued_at_unix_ms", "expires_at_unix_ms", "nonce");
    private static final Set<String> POLICY_FIELDS = Set.of("bedrock_auth_mode");
    private static final Set<String> PRINCIPAL_FIELDS = Set.of(
            "type", "bedrock_xuid", "bedrock_username", "bedrock_derived_uuid",
            "linked_java_uuid", "linked_java_name");

    private final PublicKey publicKey;
    private final Supplier<Instant> now;
    private final String endpointId;
    private final String endpointName;
    private final String orgId;
    private final String sessionId;
    private final String protocol;
    private final String bedrockAuthPolicy;
    private final String expectedIssuer;
    private final BedrockIdentityReplayCache replayCache;

    private BedrockIdentityVerifier(Builder builder) {
        this.publicKey = Objects.requireNonNull(builder.publicKey, "publicKey");
        this.now = builder.now;
        this.endpointId = optionalNonEmpty(builder.endpointId, "endpointId");
        this.endpointName = requireNonEmpty(builder.endpointName, "endpointName");
        this.orgId = optionalNonEmpty(builder.orgId, "orgId");
        this.sessionId = requireNonEmpty(builder.sessionId, "sessionId");
        this.protocol = requireNonEmpty(builder.protocol, "protocol");
        this.bedrockAuthPolicy = builder.bedrockAuthPolicy;
        this.expectedIssuer = optionalNonEmpty(builder.expectedIssuer, "expectedIssuer");
        this.replayCache = builder.replayCache;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BedrockIdentityClaims verify(GameProfile profile) throws BedrockIdentityVerificationException {
        Objects.requireNonNull(profile, "profile");
        String signedEnvelope = null;
        for (GameProfile.Property property : profile.getProperties()) {
            if (PROPERTY_NAME.equals(property.getName())) {
                if (signedEnvelope != null) {
                    throw new BedrockIdentityVerificationException("duplicate " + PROPERTY_NAME + " profile property");
                }
                signedEnvelope = property.getValue();
            }
        }
        if (signedEnvelope != null) {
            return verify(signedEnvelope, profile);
        }
        throw new BedrockIdentityVerificationException("missing " + PROPERTY_NAME + " profile property");
    }

    public BedrockIdentityClaims verify(String signedEnvelope) throws BedrockIdentityVerificationException {
        return verify(signedEnvelope, null);
    }

    private BedrockIdentityClaims verify(String signedEnvelope, GameProfile profile)
            throws BedrockIdentityVerificationException {
        Envelope envelope = decode(signedEnvelope);
        verifySignature(envelope);
        validate(envelope);
        validateScope(envelope);
        if (profile != null) {
            validateProfile(envelope, profile);
        }

        long nowUnixMs = now.get().toEpochMilli();
        if (nowUnixMs >= envelope.session.expires_at_unix_ms) {
            throw new BedrockIdentityVerificationException("identity envelope expired");
        }
        if (envelope.session.issued_at_unix_ms > nowUnixMs + 60_000) {
            throw new BedrockIdentityVerificationException("identity envelope issued too far in the future");
        }
        if (replayCache != null && !replayCache.accept(
                envelope.endpoint.id,
                envelope.session.id,
                envelope.session.nonce,
                nowUnixMs,
                envelope.session.expires_at_unix_ms)) {
            throw new BedrockIdentityVerificationException("identity envelope replayed");
        }

        return new BedrockIdentityClaims(
                envelope.issuer,
                envelope.endpoint.id,
                envelope.endpoint.name,
                envelope.endpoint.org_id,
                envelope.session.id,
                envelope.session.protocol,
                envelope.policy.bedrock_auth_mode,
                envelope.principal.type,
                envelope.principal.bedrock_xuid,
                envelope.principal.bedrock_username,
                envelope.principal.bedrock_derived_uuid,
                envelope.principal.linked_java_uuid,
                envelope.principal.linked_java_name,
                Instant.ofEpochMilli(envelope.session.issued_at_unix_ms),
                Instant.ofEpochMilli(envelope.session.expires_at_unix_ms));
    }

    private static void validateProfile(Envelope envelope, GameProfile profile)
            throws BedrockIdentityVerificationException {
        String expectedUuid;
        String expectedName;
        if (PRINCIPAL_BEDROCK_LINKED_JAVA.equals(envelope.principal.type)) {
            expectedUuid = envelope.principal.linked_java_uuid;
            expectedName = envelope.principal.linked_java_name;
        } else {
            expectedUuid = envelope.principal.bedrock_derived_uuid;
            expectedName = envelope.principal.bedrock_username;
        }
        if (!UUID.fromString(expectedUuid).equals(profile.getUniqueId()) ||
                !expectedName.equals(profile.getUsername())) {
            throw new BedrockIdentityVerificationException("identity envelope profile mismatch");
        }
    }

    private static Envelope decode(String signedEnvelope) throws BedrockIdentityVerificationException {
        validateStructure(signedEnvelope);
        try {
            Envelope envelope = GSON.fromJson(signedEnvelope, Envelope.class);
            if (envelope == null) {
                throw new BedrockIdentityVerificationException("decode envelope: empty envelope");
            }
            return envelope;
        } catch (JsonSyntaxException e) {
            throw new BedrockIdentityVerificationException("decode envelope", e);
        }
    }

    private static void validateStructure(String signedEnvelope) throws BedrockIdentityVerificationException {
        try (JsonReader reader = new JsonReader(new StringReader(signedEnvelope))) {
            validateObject(reader, "envelope", ENVELOPE_FIELDS);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new BedrockIdentityVerificationException("decode envelope: trailing JSON value");
            }
        } catch (IOException | IllegalStateException e) {
            throw new BedrockIdentityVerificationException("decode envelope", e);
        }
    }

    private static void validateObject(JsonReader reader, String object, Set<String> allowedFields)
            throws IOException, BedrockIdentityVerificationException {
        reader.beginObject();
        Set<String> seenFields = new HashSet<>();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!seenFields.add(field)) {
                throw new BedrockIdentityVerificationException("decode envelope: duplicate field");
            }
            if (!allowedFields.contains(field)) {
                throw new BedrockIdentityVerificationException("decode envelope: unknown field");
            }
            Set<String> nestedFields = nestedFields(object, field);
            if (nestedFields == null) {
                reader.skipValue();
            } else {
                validateObject(reader, field, nestedFields);
            }
        }
        reader.endObject();
    }

    private static Set<String> nestedFields(String object, String field) {
        if (!"envelope".equals(object)) {
            return null;
        }
        switch (field) {
            case "endpoint":
                return ENDPOINT_FIELDS;
            case "session":
                return SESSION_FIELDS;
            case "policy":
                return POLICY_FIELDS;
            case "principal":
                return PRINCIPAL_FIELDS;
            default:
                return null;
        }
    }

    private void verifySignature(Envelope envelope) throws BedrockIdentityVerificationException {
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(requireNonEmpty(envelope.signature, "signature"));
        } catch (IllegalArgumentException e) {
            throw new BedrockIdentityVerificationException("decode signature", e);
        }

        String originalSignature = envelope.signature;
        envelope.signature = null;
        byte[] payload = GSON.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        envelope.signature = originalSignature;

        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new BedrockIdentityVerificationException("invalid envelope signature");
            }
        } catch (GeneralSecurityException e) {
            throw new BedrockIdentityVerificationException("verify envelope signature", e);
        }
    }

    private static void validate(Envelope envelope) throws BedrockIdentityVerificationException {
        if (envelope.version != VERSION) {
            throw new BedrockIdentityVerificationException("unsupported identity envelope version " + envelope.version);
        }
        if (isEmpty(envelope.issuer) || envelope.endpoint == null ||
                isEmpty(envelope.endpoint.id) ||
                isEmpty(envelope.endpoint.name) ||
                isEmpty(envelope.endpoint.org_id)) {
            throw new BedrockIdentityVerificationException("identity envelope endpoint scope is incomplete");
        }
        if (envelope.session == null ||
                isEmpty(envelope.session.id) ||
                isEmpty(envelope.session.protocol) ||
                isEmpty(envelope.session.nonce)) {
            throw new BedrockIdentityVerificationException("identity envelope session scope is incomplete");
        }
        if (envelope.session.issued_at_unix_ms <= 0 ||
                envelope.session.expires_at_unix_ms <= envelope.session.issued_at_unix_ms ||
                envelope.session.expires_at_unix_ms - envelope.session.issued_at_unix_ms > 300_000) {
            throw new BedrockIdentityVerificationException("identity envelope expiry must be after issue time");
        }
        if (envelope.policy == null || !validPolicy(envelope.policy.bedrock_auth_mode)) {
            throw new BedrockIdentityVerificationException("identity envelope policy is invalid");
        }
        if (envelope.principal == null) {
            throw new BedrockIdentityVerificationException("identity envelope principal is incomplete");
        }
        if (!canonicalPositiveXuid(envelope.principal.bedrock_xuid) ||
                isEmpty(envelope.principal.bedrock_username) ||
                !derivedUuid(envelope.principal.bedrock_xuid).equals(envelope.principal.bedrock_derived_uuid)) {
            throw new BedrockIdentityVerificationException("Bedrock principal identity is invalid");
        }
        if (PRINCIPAL_BEDROCK_XUID.equals(envelope.principal.type)) {
            if (!POLICY_TRUSTED_BEDROCK_XUID.equals(envelope.policy.bedrock_auth_mode) ||
                    !isEmpty(envelope.principal.linked_java_uuid) || !isEmpty(envelope.principal.linked_java_name)) {
                throw new BedrockIdentityVerificationException("bedrock_xuid principal requires trusted_bedrock_xuid policy");
            }
            return;
        }
        if (PRINCIPAL_BEDROCK_LINKED_JAVA.equals(envelope.principal.type)) {
            if (isEmpty(envelope.principal.linked_java_uuid) ||
                    isEmpty(envelope.principal.linked_java_name) || !validUuid(envelope.principal.linked_java_uuid)) {
                throw new BedrockIdentityVerificationException(
                        "bedrock_linked_java principal requires xuid and linked Java identity");
            }
            return;
        }
        throw new BedrockIdentityVerificationException(
                "unsupported identity envelope principal type " + envelope.principal.type);
    }

    private void validateScope(Envelope envelope) throws BedrockIdentityVerificationException {
        if (expectedIssuer != null && !expectedIssuer.equals(envelope.issuer)) {
            throw new BedrockIdentityVerificationException("identity envelope issuer mismatch");
        }
        if ((endpointId != null && !endpointId.equals(envelope.endpoint.id)) ||
                !endpointName.equals(envelope.endpoint.name) ||
                (orgId != null && !orgId.equals(envelope.endpoint.org_id)) ||
                !sessionId.equals(envelope.session.id) ||
                !protocol.equals(envelope.session.protocol)) {
            throw new BedrockIdentityVerificationException("identity envelope scope mismatch");
        }
        if (bedrockAuthPolicy != null && !bedrockAuthPolicy.equals(envelope.policy.bedrock_auth_mode)) {
            throw new BedrockIdentityVerificationException("identity envelope policy mismatch");
        }
    }

    private static boolean validPolicy(String policy) {
        return POLICY_LINKED_JAVA_ONLY.equals(policy) || POLICY_TRUSTED_BEDROCK_XUID.equals(policy);
    }

    private static PublicKey parsePublicKey(byte[] publicKey) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey));
        } catch (GeneralSecurityException ignored) {
            if (publicKey.length != 32) {
                throw new IllegalArgumentException("publicKey must be an Ed25519 raw or X.509 public key");
            }
            try {
                byte[] y = publicKey.clone();
                boolean xOdd = (y[31] & 0x80) != 0;
                y[31] &= 0x7f;
                reverse(y);
                EdECPublicKeySpec spec = new EdECPublicKeySpec(
                        NamedParameterSpec.ED25519,
                        new EdECPoint(xOdd, new BigInteger(1, y)));
                return KeyFactory.getInstance("Ed25519").generatePublic(spec);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("publicKey must be an Ed25519 raw or X.509 public key", e);
            }
        }
    }

    private static void reverse(byte[] bytes) {
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte tmp = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = tmp;
        }
    }

    private static String requireNonEmpty(String value, String name) {
        if (isEmpty(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String optionalNonEmpty(String value, String name) {
        if (value == null) {
            return null;
        }
        return requireNonEmpty(value, name);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean canonicalPositiveXuid(String value) {
        if (isEmpty(value) || value.charAt(0) == '0' || !value.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validUuid(String value) {
        try {
            return !new UUID(0, 0).equals(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String derivedUuid(String xuid) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest(("FloodgateXUID:" + xuid).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long most = 0;
            long least = 0;
            for (int i = 0; i < 8; i++) {
                most = (most << 8) | (hash[i] & 0xffL);
                least = (least << 8) | (hash[i + 8] & 0xffL);
            }
            return new UUID(most, least).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    public static final class Builder {
        private PublicKey publicKey;
        private Supplier<Instant> now = Instant::now;
        private String endpointId;
        private String endpointName;
        private String orgId;
        private String sessionId;
        private String protocol;
        private String bedrockAuthPolicy;
        private String expectedIssuer;
        private BedrockIdentityReplayCache replayCache;

        public Builder publicKey(byte[] publicKey) {
            this.publicKey = parsePublicKey(Objects.requireNonNull(publicKey, "publicKey").clone());
            return this;
        }

        public Builder now(Instant now) {
            Objects.requireNonNull(now, "now");
            this.now = () -> now;
            return this;
        }

        public Builder now(Supplier<Instant> now) {
            this.now = Objects.requireNonNull(now, "now");
            return this;
        }

        public Builder endpointId(String endpointId) {
            this.endpointId = endpointId;
            return this;
        }

        public Builder endpointName(String endpointName) {
            this.endpointName = endpointName;
            return this;
        }

        public Builder orgId(String orgId) {
            this.orgId = orgId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder bedrockAuthPolicy(String bedrockAuthPolicy) {
            this.bedrockAuthPolicy = requireNonEmpty(bedrockAuthPolicy, "bedrockAuthPolicy");
            return this;
        }

        public Builder expectedIssuer(String expectedIssuer) {
            this.expectedIssuer = expectedIssuer;
            return this;
        }

        public Builder replayCache(BedrockIdentityReplayCache replayCache) {
            this.replayCache = replayCache;
            return this;
        }

        public BedrockIdentityVerifier build() {
            return new BedrockIdentityVerifier(this);
        }
    }

    private static final class Envelope {
        int version;
        String issuer;
        Endpoint endpoint;
        SessionScope session;
        Policy policy;
        Principal principal;
        String signature;
    }

    private static final class Endpoint {
        String id;
        String name;
        String org_id;
    }

    private static final class SessionScope {
        String id;
        String protocol;
        long issued_at_unix_ms;
        long expires_at_unix_ms;
        String nonce;
    }

    private static final class Policy {
        String bedrock_auth_mode;
    }

    private static final class Principal {
        String type;
        String bedrock_xuid;
        String bedrock_username;
        String bedrock_derived_uuid;
        String linked_java_uuid;
        String linked_java_name;
    }
}
