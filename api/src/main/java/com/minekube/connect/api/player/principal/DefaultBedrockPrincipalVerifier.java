package com.minekube.connect.api.player.principal;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DefaultBedrockPrincipalVerifier implements BedrockPrincipalVerifier {
    static final String WIRE_TYPE = "connect-bedrock-principal+jws;v=2";
    static final String CAPABILITY = "bedrock-verified-principal-v2";
    private static final int MAX_HEADER_BYTES = 2 * 1024;
    private static final int MAX_PAYLOAD_BYTES = 12 * 1024;
    private static final BigInteger ED25519_FIELD_PRIME = BigInteger.ONE.shiftLeft(255)
            .subtract(BigInteger.valueOf(19));
    private static final BigInteger ED25519_D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(ED25519_FIELD_PRIME))
            .mod(ED25519_FIELD_PRIME);
    private static final BigInteger ED25519_SQRT_M1 = BigInteger.valueOf(2)
            .modPow(ED25519_FIELD_PRIME.subtract(BigInteger.ONE).shiftRight(2),
                    ED25519_FIELD_PRIME);
    private static final BigInteger ED25519_SQRT_EXP = ED25519_FIELD_PRIME
            .add(BigInteger.valueOf(3)).shiftRight(3);
    private static final long MAX_UNIX_TIMESTAMP = 253_402_300_799L;
    private static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "version", "issuer", "trust_domain", "audience", "subject_kind",
            "canonical_xuid", "canonical_unlinked_uuid", "linked_java",
            "bedrock_display_name", "endpoint_id", "organization_id",
            "connect_session_id", "connect_session_nonce", "policy_revision",
            "source_protocol", "source_protocol_version", "iat", "nbf", "exp",
            "jti", "verification_method");
    private static final Set<String> REQUIRED_PAYLOAD_FIELDS = Set.of(
            "version", "issuer", "trust_domain", "audience", "subject_kind",
            "canonical_xuid", "canonical_unlinked_uuid", "bedrock_display_name",
            "endpoint_id", "organization_id", "connect_session_id",
            "connect_session_nonce", "policy_revision", "source_protocol",
            "source_protocol_version", "iat", "nbf", "exp", "jti",
            "verification_method");
    private static final Set<String> LINK_FIELDS = Set.of("uuid", "name", "provenance");
    private static final Set<String> PROVENANCE_FIELDS =
            Set.of("provider", "record_id", "revision", "verified_at");

    private final Map<String, PublicKey> keys;
    private final Clock clock;
    private final ReplayCache replay;

    DefaultBedrockPrincipalVerifier(VerifierConfiguration configuration) {
        this.clock = configuration.clock();
        this.replay = new ReplayCache(configuration.replayCapacity(), clock);
        Map<String, PublicKey> parsed = new HashMap<>();
        configuration.publicKeys().forEach((kid, key) -> parsed.put(kid, parsePublicKey(key)));
        this.keys = Map.copyOf(parsed);
    }

    @Override
    public VerifiedBedrockPrincipal verifyAndConsume(
            SignedPrincipalEnvelope envelope,
            TrustedProposalContext expected) throws PrincipalVerificationException {
        if (envelope == null || expected == null) throw reject(PrincipalError.MALFORMED);
        ParsedEnvelope parsed = parse(envelope.compact());
        Claims claims = parsed.claims;
        if (!validTrust(claims.issuer, 128)
                || !validTrust(claims.trustDomain, 256)
                || !validTrust(claims.audience, 256)
                || !validTrust(expected.issuer(), 128)
                || !validTrust(expected.trustDomain(), 256)
                || !validTrust(expected.audience(), 256)
                || !same(claims.issuer, expected.issuer())
                || !same(claims.trustDomain, expected.trustDomain())
                || !same(claims.audience, expected.audience())) {
            throw reject(PrincipalError.TRUST);
        }

        PublicKey key = keys.get(parsed.kid);
        if (key == null) throw reject(PrincipalError.TRUST);
        if (!verifySignature(key, parsed.signingInput, parsed.signature)) {
            throw reject(PrincipalError.SIGNATURE);
        }

        byte[] nonce = decodeCanonical16(claims.connectSessionNonce);
        decodeCanonical16(claims.jti);
        if (!validBinding(claims.sourceProtocol, claims.sourceProtocolVersion,
                claims.endpointId, claims.organizationId, claims.connectSessionId)
                || !validBinding(expected.sourceProtocol(), expected.sourceProtocolVersion(),
                expected.endpointId(), expected.organizationId(), expected.connectSessionId())
                || !MessageDigest.isEqual(nonce, expected.connectSessionNonce())
                || !same(claims.endpointId, expected.endpointId())
                || !same(claims.organizationId, expected.organizationId())
                || !same(claims.connectSessionId, expected.connectSessionId())
                || !same(claims.sourceProtocol, expected.sourceProtocol())
                || claims.sourceProtocolVersion != expected.sourceProtocolVersion()
                || claims.policyRevision != expected.policyRevision()
                || expected.policyRevision() <= 0) {
            throw reject(PrincipalError.BINDING_MISMATCH);
        }

        validateTime(claims, clock.instant().getEpochSecond());
        ImmutableVerifiedBedrockPrincipal principal = principal(claims, parsed.kid, expected);
        replay.consume(new ReplayValue(
                claims.trustDomain, claims.issuer, claims.jti, parsed.kid,
                claims.endpointId, claims.connectSessionId, nonce, claims.exp + 5));
        return principal;
    }

    private static ParsedEnvelope parse(String compact) throws PrincipalVerificationException {
        try {
            String[] parts = compact.split("\\.", -1);
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw malformed();
            }
            byte[] headerBytes = decodeCanonical(parts[0], MAX_HEADER_BYTES);
            byte[] payloadBytes = decodeCanonical(parts[1], MAX_PAYLOAD_BYTES);
            byte[] signature = decodeCanonical(parts[2], 64);
            if (signature.length != 64) throw malformed();
            Map<String, Object> header = StrictJson.parseObject(utf8(headerBytes), headerBytes.length);
            exact(header, HEADER_FIELDS, HEADER_FIELDS);
            if (!"EdDSA".equals(string(header, "alg"))
                    || !WIRE_TYPE.equals(string(header, "typ"))) throw malformed();
            String kid = bounded(string(header, "kid"), 1, 128);

            Map<String, Object> payload = StrictJson.parseObject(utf8(payloadBytes), payloadBytes.length);
            exact(payload, PAYLOAD_FIELDS, REQUIRED_PAYLOAD_FIELDS);
            Claims claims = Claims.from(payload);
            return new ParsedEnvelope(kid, claims,
                    (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), signature);
        } catch (IllegalArgumentException | CharacterCodingException ignored) {
            throw reject(PrincipalError.MALFORMED);
        }
    }

    private static ImmutableVerifiedBedrockPrincipal principal(
            Claims claims,
            String kid,
            TrustedProposalContext expected) throws PrincipalVerificationException {
        if (claims.version != 2
                || claims.canonicalXuid.length() > 19
                || claims.canonicalXuid.isEmpty()
                || claims.canonicalXuid.charAt(0) == '0') {
            throw reject(PrincipalError.IDENTITY);
        }
        long xuid;
        try {
            xuid = Long.parseUnsignedLong(claims.canonicalXuid);
        } catch (NumberFormatException ignored) {
            throw reject(PrincipalError.IDENTITY);
        }
        if (Long.compareUnsigned(xuid, 0L) <= 0
                || !Long.toUnsignedString(xuid).equals(claims.canonicalXuid)) {
            throw reject(PrincipalError.IDENTITY);
        }
        UUID unlinked = canonicalUuid(claims.canonicalUnlinkedUuid, PrincipalError.IDENTITY);
        UUID expectedUuid = new UUID(0L, xuid);
        if (!unlinked.equals(expectedUuid)
                || claims.bedrockDisplayName.isEmpty()
                || utf8Length(claims.bedrockDisplayName) > 64
                || !validVerificationMethod(claims.verificationMethod)) {
            throw reject(PrincipalError.IDENTITY);
        }

        SubjectKind kind = SubjectKind.fromWireName(claims.subjectKind);
        VerifiedLinkedJavaIdentity linked = null;
        if (kind == SubjectKind.BEDROCK_XUID) {
            if (claims.linkedJava != null) throw reject(PrincipalError.LINK);
        } else {
            if (claims.linkedJava == null) throw reject(PrincipalError.LINK);
            Linked link = claims.linkedJava;
            UUID javaUuid = canonicalUuid(link.uuid, PrincipalError.LINK);
            if (!validJavaName(link.name)
                    || !"moxy_account_link_v1".equals(link.provider)
                    || link.recordId.isEmpty()
                    || utf8Length(link.recordId) > 128
                    || link.revision <= 0
                    || !numericDate(link.verifiedAt)
                    || Math.abs(link.verifiedAt - claims.iat) > 5) {
                throw reject(PrincipalError.LINK);
            }
            linked = new VerifiedLinkedJavaIdentity(javaUuid, link.name,
                    new LinkProvenance(link.provider, link.recordId, link.revision,
                            Instant.ofEpochSecond(link.verifiedAt)));
        }
        return new ImmutableVerifiedBedrockPrincipal(
                kind, new CanonicalXuid(claims.canonicalXuid), unlinked, linked,
                claims.bedrockDisplayName,
                new VerificationEvidence(kid, claims.verificationMethod,
                        Instant.ofEpochSecond(claims.iat), Instant.ofEpochSecond(claims.nbf),
                        Instant.ofEpochSecond(claims.exp)),
                new PrincipalBindings(expected.issuer(), expected.trustDomain(), expected.audience(),
                        expected.endpointId(), expected.organizationId(), expected.connectSessionId(),
                        expected.connectSessionNonce(), expected.sourceProtocol(),
                        expected.sourceProtocolVersion(), expected.policyRevision()));
    }

    private static void validateTime(Claims claims, long now) throws PrincipalVerificationException {
        if (!numericDate(claims.iat) || !numericDate(claims.nbf) || !numericDate(claims.exp)
                || claims.nbf > claims.iat || claims.iat > claims.exp
                || claims.exp - claims.iat > 30
                || claims.nbf > now + 5 || claims.iat > now + 5 || claims.exp < now - 5) {
            throw reject(PrincipalError.TIME);
        }
    }

    private static boolean numericDate(long value) {
        return value >= 0 && value <= MAX_UNIX_TIMESTAMP;
    }

    private static boolean validVerificationMethod(String value) {
        return "minecraft_legacy_chain+client_jwt+ecdh_v1".equals(value)
                || "minecraft_full_jwks+client_jwt+ecdh_v1".equals(value);
    }

    private static boolean validJavaName(String value) {
        if (value.isEmpty() || value.length() > 16) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '_') return false;
        }
        return true;
    }

    private static UUID canonicalUuid(String value, PrincipalError error)
            throws PrincipalVerificationException {
        try {
            UUID uuid = UUID.fromString(value);
            if (value.length() != 36 || !uuid.toString().equals(value)) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException ignored) {
            throw reject(error);
        }
    }

    private static boolean validBinding(
            String protocol, int version, String endpoint, String organization, String session) {
        return "bedrock".equals(protocol) && version >= 1
                && boundedBinding(endpoint) && boundedBinding(organization) && boundedBinding(session);
    }

    private static boolean boundedBinding(String value) {
        return value != null && utf8Length(value) >= 1 && utf8Length(value) <= 128;
    }

    private static boolean validTrust(String value, int maximum) {
        return value != null && utf8Length(value) >= 1 && utf8Length(value) <= maximum;
    }

    private static boolean same(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean verifySignature(PublicKey key, byte[] input, byte[] signature)
            throws PrincipalVerificationException {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(input);
            return verifier.verify(signature);
        } catch (GeneralSecurityException ignored) {
            throw reject(PrincipalError.INTERNAL);
        }
    }

    private static byte[] decodeCanonical16(String value) throws PrincipalVerificationException {
        if (value.length() != 22) throw reject(PrincipalError.MALFORMED);
        try {
            byte[] decoded = decodeCanonical(value, 16);
            if (decoded.length != 16) throw malformed();
            return decoded;
        } catch (IllegalArgumentException ignored) {
            throw reject(PrincipalError.MALFORMED);
        }
    }

    private static byte[] decodeCanonical(String value, int maximum) {
        if (value.indexOf('=') >= 0) throw malformed();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length > maximum
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            throw malformed();
        }
        return decoded;
    }

    private static String utf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static PublicKey parsePublicKey(byte[] raw) {
        try {
            byte[] y = raw.clone();
            boolean xOdd = (y[31] & 0x80) != 0;
            y[31] &= 0x7f;
            for (int left = 0, right = y.length - 1; left < right; left++, right--) {
                byte swap = y[left];
                y[left] = y[right];
                y[right] = swap;
            }
            BigInteger yCoordinate = new BigInteger(1, y);
            if (yCoordinate.compareTo(ED25519_FIELD_PRIME) >= 0
                    || !validEd25519Point(yCoordinate, xOdd)) {
                throw new IllegalArgumentException("invalid verifier public key");
            }
            return KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(
                    NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, y))));
        } catch (GeneralSecurityException | RuntimeException ignored) {
            throw new IllegalArgumentException("invalid verifier public key");
        }
    }

    private static boolean validEd25519Point(BigInteger y, boolean xOdd) {
        BigInteger ySquared = y.multiply(y).mod(ED25519_FIELD_PRIME);
        BigInteger denominator = ED25519_D.multiply(ySquared).add(BigInteger.ONE)
                .mod(ED25519_FIELD_PRIME);
        if (denominator.signum() == 0) return false;
        BigInteger xSquared = ySquared.subtract(BigInteger.ONE)
                .multiply(denominator.modInverse(ED25519_FIELD_PRIME))
                .mod(ED25519_FIELD_PRIME);
        BigInteger x = xSquared.modPow(ED25519_SQRT_EXP, ED25519_FIELD_PRIME);
        if (!x.multiply(x).mod(ED25519_FIELD_PRIME).equals(xSquared)) {
            x = x.multiply(ED25519_SQRT_M1).mod(ED25519_FIELD_PRIME);
        }
        if (!x.multiply(x).mod(ED25519_FIELD_PRIME).equals(xSquared)
                || x.signum() == 0) {
            return false;
        }
        if (x.testBit(0) != xOdd) x = ED25519_FIELD_PRIME.subtract(x);
        return !smallOrder(x, y);
    }

    private static boolean smallOrder(BigInteger x, BigInteger y) {
        for (int count = 0; count < 3; count++) {
            BigInteger product = ED25519_D.multiply(x).multiply(x).multiply(y).multiply(y)
                    .mod(ED25519_FIELD_PRIME);
            BigInteger nextX = x.multiply(y).shiftLeft(1)
                    .multiply(BigInteger.ONE.add(product).modInverse(ED25519_FIELD_PRIME))
                    .mod(ED25519_FIELD_PRIME);
            BigInteger nextY = y.multiply(y).add(x.multiply(x))
                    .multiply(BigInteger.ONE.subtract(product).mod(ED25519_FIELD_PRIME)
                            .modInverse(ED25519_FIELD_PRIME))
                    .mod(ED25519_FIELD_PRIME);
            x = nextX;
            y = nextY;
        }
        return x.signum() == 0 && y.equals(BigInteger.ONE);
    }

    private static void exact(Map<String, Object> object, Set<String> allowed, Set<String> required) {
        if (!allowed.containsAll(object.keySet()) || !object.keySet().containsAll(required)) {
            throw malformed();
        }
    }

    private static String string(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof String)) throw malformed();
        return (String) value;
    }

    private static long integer(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof Long)) throw malformed();
        return (Long) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> parent, String name) {
        Object value = parent.get(name);
        if (!(value instanceof Map)) throw malformed();
        return (Map<String, Object>) value;
    }

    private static String bounded(String value, int minimum, int maximum) {
        int length = utf8Length(value);
        if (length < minimum || length > maximum) throw malformed();
        return value;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(PrincipalError.MALFORMED.name());
    }

    private static PrincipalVerificationException reject(PrincipalError error) {
        return new PrincipalVerificationException(error);
    }

    private static final class ParsedEnvelope {
        private final String kid;
        private final Claims claims;
        private final byte[] signingInput;
        private final byte[] signature;

        private ParsedEnvelope(String kid, Claims claims, byte[] signingInput, byte[] signature) {
            this.kid = kid;
            this.claims = claims;
            this.signingInput = signingInput;
            this.signature = signature;
        }
    }

    private static final class Claims {
        private int version;
        private String issuer;
        private String trustDomain;
        private String audience;
        private String subjectKind;
        private String canonicalXuid;
        private String canonicalUnlinkedUuid;
        private Linked linkedJava;
        private String bedrockDisplayName;
        private String endpointId;
        private String organizationId;
        private String connectSessionId;
        private String connectSessionNonce;
        private long policyRevision;
        private String sourceProtocol;
        private int sourceProtocolVersion;
        private long iat;
        private long nbf;
        private long exp;
        private String jti;
        private String verificationMethod;

        private static Claims from(Map<String, Object> value) {
            Claims claims = new Claims();
            long version = integer(value, "version");
            long protocolVersion = integer(value, "source_protocol_version");
            if (version < Integer.MIN_VALUE || version > Integer.MAX_VALUE
                    || protocolVersion < Integer.MIN_VALUE || protocolVersion > Integer.MAX_VALUE) {
                throw malformed();
            }
            claims.version = (int) version;
            claims.issuer = bounded(string(value, "issuer"), 1, 128);
            claims.trustDomain = bounded(string(value, "trust_domain"), 1, 256);
            claims.audience = bounded(string(value, "audience"), 1, 256);
            claims.subjectKind = string(value, "subject_kind");
            claims.canonicalXuid = string(value, "canonical_xuid");
            claims.canonicalUnlinkedUuid = string(value, "canonical_unlinked_uuid");
            claims.bedrockDisplayName = string(value, "bedrock_display_name");
            claims.endpointId = bounded(string(value, "endpoint_id"), 1, 128);
            claims.organizationId = bounded(string(value, "organization_id"), 1, 128);
            claims.connectSessionId = bounded(string(value, "connect_session_id"), 1, 128);
            claims.connectSessionNonce = string(value, "connect_session_nonce");
            claims.policyRevision = integer(value, "policy_revision");
            claims.sourceProtocol = string(value, "source_protocol");
            claims.sourceProtocolVersion = (int) protocolVersion;
            claims.iat = integer(value, "iat");
            claims.nbf = integer(value, "nbf");
            claims.exp = integer(value, "exp");
            claims.jti = string(value, "jti");
            claims.verificationMethod = string(value, "verification_method");
            if (value.containsKey("linked_java")) claims.linkedJava = Linked.from(object(value, "linked_java"));
            return claims;
        }
    }

    private static final class Linked {
        private String uuid;
        private String name;
        private String provider;
        private String recordId;
        private long revision;
        private long verifiedAt;

        private static Linked from(Map<String, Object> value) {
            exact(value, LINK_FIELDS, LINK_FIELDS);
            Map<String, Object> provenance = object(value, "provenance");
            exact(provenance, PROVENANCE_FIELDS, PROVENANCE_FIELDS);
            Linked linked = new Linked();
            linked.uuid = string(value, "uuid");
            linked.name = string(value, "name");
            linked.provider = string(provenance, "provider");
            linked.recordId = string(provenance, "record_id");
            linked.revision = integer(provenance, "revision");
            linked.verifiedAt = integer(provenance, "verified_at");
            return linked;
        }
    }

    private static final class ReplayValue {
        private final String trustDomain;
        private final String issuer;
        private final String jti;
        @SuppressWarnings("unused") private final String kid;
        @SuppressWarnings("unused") private final String endpointId;
        @SuppressWarnings("unused") private final String sessionId;
        @SuppressWarnings("unused") private final byte[] nonce;
        private final long expiresAt;

        private ReplayValue(String trustDomain, String issuer, String jti, String kid,
                String endpointId, String sessionId, byte[] nonce, long expiresAt) {
            this.trustDomain = trustDomain;
            this.issuer = issuer;
            this.jti = jti;
            this.kid = kid;
            this.endpointId = endpointId;
            this.sessionId = sessionId;
            this.nonce = nonce.clone();
            this.expiresAt = expiresAt;
        }
    }

    private static final class ReplayCache {
        private final int capacity;
        private final Clock clock;
        private final Map<String, ReplayValue> entries = new HashMap<>();

        private ReplayCache(int capacity, Clock clock) {
            this.capacity = capacity;
            this.clock = clock;
        }

        private synchronized void consume(ReplayValue value) throws PrincipalVerificationException {
            long now = clock.instant().getEpochSecond();
            String key = value.trustDomain + '\0' + value.issuer + '\0' + value.jti;
            ReplayValue existing = entries.get(key);
            if (existing != null) {
                if (now <= existing.expiresAt) throw reject(PrincipalError.REPLAY);
                entries.remove(key);
            }
            if (entries.size() >= capacity) {
                int removed = 0;
                Iterator<Map.Entry<String, ReplayValue>> iterator = entries.entrySet().iterator();
                while (iterator.hasNext() && removed < 64) {
                    if (now > iterator.next().getValue().expiresAt) {
                        iterator.remove();
                        removed++;
                    }
                }
            }
            if (entries.size() >= capacity) throw reject(PrincipalError.CAPACITY);
            entries.put(key, value);
        }
    }
}
