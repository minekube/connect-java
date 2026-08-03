package com.minekube.connect.principal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minekube.connect.api.player.principal.BedrockPrincipalVerifierFactory;
import com.minekube.connect.api.player.principal.PrincipalError;
import com.minekube.connect.api.player.principal.PrincipalVerificationException;
import com.minekube.connect.api.player.principal.SignedPrincipalEnvelope;
import com.minekube.connect.api.player.principal.TrustedProposalContext;
import com.minekube.connect.api.player.principal.VerifierConfiguration;
import com.minekube.connect.watch.SessionProposal;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import org.junit.jupiter.api.Test;

class PrincipalPrivacyTest {
    @Test
    void principalAndErrorsDoNotSerializeOrLogRawIdentityMaterial() throws Exception {
        JsonObject vector = linkedVector();
        JsonObject context = vector.getAsJsonObject("trusted_context");
        String compact = vector.get("compact_jws").getAsString();
        var verifier = BedrockPrincipalVerifierFactory.create(VerifierConfiguration.builder()
                .publicKey("connect-v2-test", Base64.getUrlDecoder()
                        .decode("diQm8c6MI-Zwn1nie8hq4wqf3mYLuI96uJBC6NHCTDg"))
                .clock(Clock.fixed(Instant.ofEpochSecond(
                        vector.get("verification_time_unix").getAsLong()), ZoneOffset.UTC))
                .build());
        TrustedProposalContext trusted = new TrustedProposalContext(
                context.get("issuer").getAsString(),
                context.get("trust_domain").getAsString(),
                context.get("audience").getAsString(),
                context.get("endpoint_id").getAsString(),
                context.get("organization_id").getAsString(),
                context.get("connect_session_id").getAsString(),
                Base64.getUrlDecoder().decode(context.get("connect_session_nonce").getAsString()),
                context.get("source_protocol").getAsString(),
                context.get("source_protocol_version").getAsInt(),
                context.get("policy_revision").getAsLong());
        var principal = verifier.verifyAndConsume(SignedPrincipalEnvelope.of(compact), trusted);

        String capture = principal + "\n" + new Gson().toJson(principal) + "\n"
                + principal.xuid() + "\n" + principal.linkedJava().orElseThrow();
        for (String forbidden : new String[] {
                "BedrockOne", "JavaOne", "record-test-1", "123e4567-e89b-12d3-a456-426614174000",
                compact, "AAAAAAAAAAAAAAAAAAAAAA", "AgICAgICAgICAgICAgICAg"
        }) {
            assertFalse(capture.contains(forbidden), forbidden);
        }

        PrincipalVerificationException error = new PrincipalVerificationException(PrincipalError.SIGNATURE);
        assertTrue(error.toString().endsWith(PrincipalError.SIGNATURE.name()));
        assertNull(error.getCause());
        assertTrue(error.getStackTrace().length == 0);
    }

    @Test
    void proposalStringCannotExposeEnvelopeNonceOrProfile() {
        String envelope = "compact-envelope-sentinel";
        String display = "Bedrock-display-sentinel";
        SessionProposal proposal = new SessionProposal(Session.newBuilder()
                .setId("session-correlation")
                .setConnectSessionNonce(ByteString.copyFromUtf8("nonce-sentinel-1"))
                .setSignedBedrockPrincipalV2(ByteString.copyFromUtf8(envelope))
                .setPlayer(minekube.connect.v1alpha1.WatchServiceOuterClass.Player.newBuilder()
                        .setProfile(minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile
                                .newBuilder().setName(display)))
                .build(), ignored -> { });

        String capture = proposal.toString() + proposal.getSession();
        assertTrue(proposal.hasBedrockPrincipalV2());
        assertFalse(capture.contains(envelope));
        assertFalse(capture.contains("nonce-sentinel-1"));
        assertFalse(proposal.toString().contains(display));
    }

    private JsonObject linkedVector() {
        try (var reader = new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream(
                        "/bedrock-principal-v2/core-vectors.json")), StandardCharsets.UTF_8)) {
            JsonArray vectors = JsonParser.parseReader(reader).getAsJsonArray();
            for (var value : vectors) {
                JsonObject vector = value.getAsJsonObject();
                if ("valid-linked".equals(vector.get("name").getAsString())) return vector;
            }
            throw new AssertionError("missing valid-linked vector");
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }
}
