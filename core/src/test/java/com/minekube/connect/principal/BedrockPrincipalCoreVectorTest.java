package com.minekube.connect.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.minekube.connect.api.player.principal.BedrockPrincipalVerifier;
import com.minekube.connect.api.player.principal.BedrockPrincipalVerifierFactory;
import com.minekube.connect.api.player.principal.PrincipalError;
import com.minekube.connect.api.player.principal.PrincipalVerificationException;
import com.minekube.connect.api.player.principal.SignedPrincipalEnvelope;
import com.minekube.connect.api.player.principal.TrustedProposalContext;
import com.minekube.connect.api.player.principal.VerifierConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BedrockPrincipalCoreVectorTest {
    private static final String VECTOR_SHA256 =
            "4f2a442ee71bfd35af2ef1f3944489d17551aa77fed2c08220f2aa77032b6196";
    private static final byte[] TEST_PUBLIC_KEY = Base64.getUrlDecoder()
            .decode("diQm8c6MI-Zwn1nie8hq4wqf3mYLuI96uJBC6NHCTDg");

    @Test
    void verifiesAgainstLiteralCoreVectorOutcomes() throws Exception {
        byte[] literal = resourceBytes("/bedrock-principal-v2/core-vectors.json");
        assertEquals(VECTOR_SHA256, hex(MessageDigest.getInstance("SHA-256").digest(literal)));
        Vector[] vectors = new Gson().fromJson(
                new InputStreamReader(
                        Objects.requireNonNull(getClass().getResourceAsStream(
                                "/bedrock-principal-v2/core-vectors.json")),
                        StandardCharsets.UTF_8),
                Vector[].class);
        assertEquals(6, vectors.length);

        for (Vector vector : vectors) {
            BedrockPrincipalVerifier verifier = BedrockPrincipalVerifierFactory.create(
                    VerifierConfiguration.builder()
                            .publicKey("connect-v2-test", TEST_PUBLIC_KEY)
                            .clock(Clock.fixed(
                                    Instant.ofEpochSecond(vector.verificationTimeUnix),
                                    ZoneOffset.UTC))
                            .build());
            TrustedProposalContext expected = vector.trustedContext.toContext();
            if (!"OK".equals(vector.expectedError)) {
                PrincipalVerificationException error = assertThrows(
                        PrincipalVerificationException.class,
                        () -> verifier.verifyAndConsume(
                                SignedPrincipalEnvelope.of(vector.compactJws), expected),
                        vector.name);
                assertEquals(PrincipalError.valueOf(vector.expectedError), error.error(), vector.name);
                continue;
            }

            var principal = verifier.verifyAndConsume(
                    SignedPrincipalEnvelope.of(vector.compactJws), expected);
            ExpectedPrincipal literalPrincipal = vector.expectedPrincipal;
            assertNotNull(literalPrincipal, vector.name);
            assertEquals(literalPrincipal.subjectKind, principal.subjectKind().wireName(), vector.name);
            assertEquals(literalPrincipal.canonicalXuid, principal.xuid().value(), vector.name);
            assertEquals(UUID.fromString(literalPrincipal.canonicalUnlinkedUuid),
                    principal.canonicalUnlinkedUuid(), vector.name);
            assertEquals(literalPrincipal.bedrockDisplayName, principal.bedrockDisplayName(), vector.name);
            assertEquals(UUID.fromString(literalPrincipal.effectiveUuid),
                    principal.effectiveGameProfile().uuid(), vector.name);
            assertEquals(literalPrincipal.effectiveName,
                    principal.effectiveGameProfile().name(), vector.name);
            assertEquals(literalPrincipal.verificationMethod,
                    principal.verification().verificationMethod(), vector.name);
            assertEquals(literalPrincipal.kid, principal.verification().kid(), vector.name);
            assertEquals(literalPrincipal.policyRevision,
                    principal.bindings().policyRevision(), vector.name);
            if (literalPrincipal.linkedJava == null) {
                assertTrue(principal.linkedJava().isEmpty(), vector.name);
            } else {
                var linked = principal.linkedJava().orElseThrow();
                assertEquals(UUID.fromString(literalPrincipal.linkedJava.uuid), linked.uuid(), vector.name);
                assertEquals(literalPrincipal.linkedJava.name, linked.name(), vector.name);
                assertEquals(literalPrincipal.linkedJava.provider,
                        linked.provenance().provider(), vector.name);
                assertEquals(literalPrincipal.linkedJava.recordId,
                        linked.provenance().recordId(), vector.name);
                assertEquals(literalPrincipal.linkedJava.revision,
                        linked.provenance().revision(), vector.name);
                assertEquals(Instant.ofEpochSecond(literalPrincipal.linkedJava.verifiedAtUnix),
                        linked.provenance().verifiedAt(), vector.name);
            }
        }
    }

    @Test
    void consumesReplayExactlyOnce() throws Exception {
        Vector vector = Arrays.stream(vectors())
                .filter(candidate -> candidate.name.equals("valid-unlinked"))
                .findFirst()
                .orElseThrow();
        BedrockPrincipalVerifier verifier = BedrockPrincipalVerifierFactory.create(
                VerifierConfiguration.builder()
                        .publicKey("connect-v2-test", TEST_PUBLIC_KEY)
                        .clock(Clock.fixed(
                                Instant.ofEpochSecond(vector.verificationTimeUnix), ZoneOffset.UTC))
                        .build());
        SignedPrincipalEnvelope envelope = SignedPrincipalEnvelope.of(vector.compactJws);
        assertNotNull(verifier.verifyAndConsume(envelope, vector.trustedContext.toContext()));
        PrincipalVerificationException error = assertThrows(
                PrincipalVerificationException.class,
                () -> verifier.verifyAndConsume(envelope, vector.trustedContext.toContext()));
        assertEquals(PrincipalError.REPLAY, error.error());
    }

    @Test
    void concurrentReplayConsumptionHasOneAnonymousWinner() throws Exception {
        Vector vector = Arrays.stream(vectors())
                .filter(candidate -> candidate.name.equals("valid-unlinked"))
                .findFirst().orElseThrow();
        BedrockPrincipalVerifier verifier = BedrockPrincipalVerifierFactory.create(
                VerifierConfiguration.builder().publicKey("connect-v2-test", TEST_PUBLIC_KEY)
                        .clock(Clock.fixed(Instant.ofEpochSecond(
                                vector.verificationTimeUnix), ZoneOffset.UTC)).build());
        SignedPrincipalEnvelope envelope = SignedPrincipalEnvelope.of(vector.compactJws);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(16);
        try {
            List<java.util.concurrent.Future<?>> results = new java.util.ArrayList<>();
            for (int index = 0; index < 32; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        verifier.verifyAndConsume(envelope, vector.trustedContext.toContext());
                        successes.incrementAndGet();
                    } catch (PrincipalVerificationException error) {
                        if (error.error() != PrincipalError.REPLAY) throw error;
                        replays.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) result.get();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, successes.get());
        assertEquals(31, replays.get());
    }

    private Vector[] vectors() {
        return new Gson().fromJson(
                new InputStreamReader(
                        Objects.requireNonNull(getClass().getResourceAsStream(
                                "/bedrock-principal-v2/core-vectors.json")),
                        StandardCharsets.UTF_8),
                Vector[].class);
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (var in = Objects.requireNonNull(
                BedrockPrincipalCoreVectorTest.class.getResourceAsStream(name))) {
            return in.readAllBytes();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static final class Vector {
        String name;
        @SerializedName("compact_jws") String compactJws;
        @SerializedName("trusted_context") TrustedContext trustedContext;
        @SerializedName("verification_time_unix") long verificationTimeUnix;
        @SerializedName("expected_error") String expectedError;
        @SerializedName("expected_principal") ExpectedPrincipal expectedPrincipal;
    }

    private static final class TrustedContext {
        String issuer;
        @SerializedName("trust_domain") String trustDomain;
        String audience;
        @SerializedName("endpoint_id") String endpointId;
        @SerializedName("organization_id") String organizationId;
        @SerializedName("connect_session_id") String connectSessionId;
        @SerializedName("connect_session_nonce") String connectSessionNonce;
        @SerializedName("source_protocol") String sourceProtocol;
        @SerializedName("source_protocol_version") int sourceProtocolVersion;
        @SerializedName("policy_revision") long policyRevision;

        TrustedProposalContext toContext() {
            return new TrustedProposalContext(
                    issuer, trustDomain, audience, endpointId, organizationId, connectSessionId,
                    Base64.getUrlDecoder().decode(connectSessionNonce), sourceProtocol,
                    sourceProtocolVersion, policyRevision);
        }
    }

    private static final class ExpectedPrincipal {
        @SerializedName("subject_kind") String subjectKind;
        @SerializedName("canonical_xuid") String canonicalXuid;
        @SerializedName("canonical_unlinked_uuid") String canonicalUnlinkedUuid;
        @SerializedName("bedrock_display_name") String bedrockDisplayName;
        @SerializedName("effective_uuid") String effectiveUuid;
        @SerializedName("effective_name") String effectiveName;
        @SerializedName("verification_method") String verificationMethod;
        String kid;
        @SerializedName("policy_revision") long policyRevision;
        @SerializedName("linked_java") ExpectedLinkedJava linkedJava;
    }

    private static final class ExpectedLinkedJava {
        String uuid;
        String name;
        String provider;
        @SerializedName("record_id") String recordId;
        long revision;
        @SerializedName("verified_at_unix") long verifiedAtUnix;
    }
}
