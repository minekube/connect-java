package com.minekube.connect.api.player.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.minekube.connect.api.player.GameProfile;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BedrockIdentityFixtureManifestTest {
    @Test
    void moxyOwnedV1FixturesMatchPublishedSha256ManifestByteForByte() throws Exception {
        Manifest manifest;
        try (InputStream input = resource("manifest.json")) {
            manifest = new Gson().fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), Manifest.class);
        }

        assertEquals("minekube-bedrock-identity-v1-test-vectors", manifest.schema);
        assertEquals(23, manifest.vectors.size());
        for (Vector vector : manifest.vectors) {
            byte[] bytes;
            try (InputStream input = resource(vector.file)) {
                bytes = input.readAllBytes();
            }
            assertEquals(vector.sha256, hex(MessageDigest.getInstance("SHA-256").digest(bytes)), vector.file);
        }
    }

    @Test
    void verifiesTheMoxySignedUnlinkedBedrockFixture() throws Exception {
        Manifest manifest;
        try (InputStream input = resource("manifest.json")) {
            manifest = new Gson().fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), Manifest.class);
        }
        String envelope;
        try (InputStream input = resource("v1-bedrock-xuid-valid.json")) {
            envelope = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        BedrockIdentityClaims claims = BedrockIdentityVerifier.builder()
                .publicKey(Base64.getDecoder().decode(manifest.public_key_base64))
                .expectedIssuer("minekube-connect")
                .now(Instant.ofEpochMilli(manifest.verification_time_unix_ms))
                .endpointId("endpoint-id")
                .endpointName("fixture-endpoint")
                .orgId("org-id")
                .sessionId("session-id")
                .protocol("bedrock")
                .bedrockAuthPolicy("trusted_bedrock_xuid")
                .build()
                .verify(envelope);

        assertEquals("bedrock_xuid", claims.getPrincipalType());
    }

    @Test
    void appliesMoxyFixtureAcceptanceSemanticsToEveryEnvelopeVector() throws Exception {
        Manifest manifest;
        try (InputStream input = resource("manifest.json")) {
            manifest = new Gson().fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), Manifest.class);
        }
        for (Vector vector : manifest.vectors) {
            if ("v1-duplicate-property-profile.json".equals(vector.file)) {
                continue;
            }
            String envelope;
            try (InputStream input = resource(vector.file)) {
                envelope = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            BedrockIdentityVerifier verifier = BedrockIdentityVerifier.builder()
                    .publicKey(Base64.getDecoder().decode(manifest.public_key_base64))
                    .expectedIssuer("minekube-connect")
                    .now(Instant.ofEpochMilli(manifest.verification_time_unix_ms))
                    .endpointId("endpoint-id")
                    .endpointName("fixture-endpoint")
                    .orgId("org-id")
                    .sessionId("session-id")
                    .protocol("bedrock")
                    .build();
            if (vector.expected.startsWith("valid_")) {
                assertDoesNotThrow(() -> verifier.verify(envelope), vector.file);
            } else {
                assertThrows(BedrockIdentityVerificationException.class,
                        () -> verifier.verify(envelope), vector.file);
            }
        }
    }

    @Test
    void rejectsTheMoxyDuplicateReservedPropertyProfileFixture() throws Exception {
        Manifest manifest;
        try (InputStream input = resource("manifest.json")) {
            manifest = new Gson().fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), Manifest.class);
        }
        ProfileFixture fixture;
        try (InputStream input = resource("v1-duplicate-property-profile.json")) {
            fixture = new Gson().fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), ProfileFixture.class);
        }
        GameProfile profile = new GameProfile("BedrockFox",
                UUID.fromString("cafc7598-0ef3-527f-8f28-60af2d9ca6bc"),
                fixture.properties.stream().map(property -> new GameProfile.Property(property.name, property.value, ""))
                        .toList());
        BedrockIdentityVerifier verifier = BedrockIdentityVerifier.builder()
                .publicKey(Base64.getDecoder().decode(manifest.public_key_base64))
                .expectedIssuer("minekube-connect")
                .now(Instant.ofEpochMilli(manifest.verification_time_unix_ms))
                .endpointId("endpoint-id")
                .endpointName("fixture-endpoint")
                .orgId("org-id")
                .sessionId("session-id")
                .protocol("bedrock")
                .build();

        assertThrows(BedrockIdentityVerificationException.class, () -> verifier.verify(profile));
    }

    private static InputStream resource(String file) {
        InputStream input = BedrockIdentityFixtureManifestTest.class.getResourceAsStream(
                "/bedrock-identity-v1/" + file);
        if (input == null) {
            throw new AssertionError("missing Moxy fixture " + file);
        }
        return input;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value));
        }
        return out.toString();
    }

    private static final class Manifest {
        String schema;
        String public_key_base64;
        long verification_time_unix_ms;
        List<Vector> vectors;
    }

    private static final class Vector {
        String file;
        String sha256;
        String expected;
    }

    private static final class ProfileFixture {
        List<PropertyFixture> properties;
    }

    private static final class PropertyFixture {
        String name;
        String value;
    }
}
