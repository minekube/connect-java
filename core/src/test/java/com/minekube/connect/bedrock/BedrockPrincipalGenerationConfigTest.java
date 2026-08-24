package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.config.ConfigLoader;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.config.ProxyConnectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BedrockPrincipalGenerationConfigTest {
    @TempDir Path tempDir;

    @Test
    void newlyGeneratedServerAndProxyConfigsDefaultToV2WarnUntilProducerShips() throws Exception {
        ConnectConfig server = load(ConnectConfig.class, tempDir.resolve("server"));
        ProxyConnectConfig proxy = load(ProxyConnectConfig.class, tempDir.resolve("proxy"));
        for (ConnectConfig config : new ConnectConfig[] {server, proxy}) {
            // Interim default (Track B): generation 0 + warn keeps fresh installs on the
            // working v1 identity path until the v2 producer (metadata + signed envelopes)
            // ships. Generation 2 + require rejects every Bedrock join with READINESS when
            // the producer is absent (see moxy kanban t_87f2966f).
            assertEquals(0, config.getBedrockPrincipal().getConfigGeneration());
            assertEquals("warn", config.getBedrockPrincipal().getMode());
            assertEquals("minekube-connect", config.getBedrockPrincipal().getIssuer());
            assertEquals("urn:minekube:connect:production", config.getBedrockPrincipal().getTrustDomain());
            assertEquals("urn:minekube:connect:bedrock-principal:v2",
                    config.getBedrockPrincipal().getAudience());
            assertFalse(BedrockPrincipalConfiguration.from(config.getBedrockPrincipal()).isCapable());
        }
    }

    @Test
    void generationOneFileRemainsByteIdenticalAndNonAdvertising() throws Exception {
        Path directory = tempDir.resolve("legacy");
        Files.createDirectories(directory);
        Path file = directory.resolve("config.yml");
        String legacy = String.join("\n",
                "endpoint: legacy",
                "allow-offline-mode-players: false",
                "bedrock-identity:",
                "  enforcement: warn",
                "  metadata-url: https://watch-connect.minekube.net/.well-known/minekube-connect/bedrock-identity-keys.json",
                "  expected-issuer: minekube-connect",
                "  expected-policy: trusted_bedrock_xuid",
                "metrics:",
                "  disabled: true",
                "  uuid: 00000000-0000-0000-0000-000000000000",
                "config-version: 1",
                "");
        Files.writeString(file, legacy);

        ConnectConfig config = load(ConnectConfig.class, directory);
        assertEquals(legacy, Files.readString(file));
        assertEquals(0, config.getBedrockPrincipal().getConfigGeneration());
        assertFalse(BedrockPrincipalConfiguration.from(config.getBedrockPrincipal()).isCapable());
        assertEquals("warn", config.getBedrockIdentity().getEnforcement());
    }

    @Test
    void onlyExactGenerationTwoRequireIsCapable() {
        ConnectConfig.BedrockPrincipalConfig config = new ConnectConfig().getBedrockPrincipal();
        assertFalse(BedrockPrincipalConfiguration.from(config).isCapable());
        TestFields.set(config, "configGeneration", 2);
        TestFields.set(config, "mode", "require");
        TestFields.set(config, "issuer", "minekube-connect");
        TestFields.set(config, "trustDomain", "urn:minekube:connect:production");
        TestFields.set(config, "audience", "urn:minekube:connect:bedrock-principal:v2");
        TestFields.set(config, "metadataOrigin", "https://connect.minekube.com");
        assertTrue(BedrockPrincipalConfiguration.from(config).isCapable());
        TestFields.set(config, "mode", "warn");
        assertFalse(BedrockPrincipalConfiguration.from(config).isCapable());
        TestFields.set(config, "mode", "REQUIRE");
        assertFalse(BedrockPrincipalConfiguration.from(config).isCapable());
        TestFields.set(config, "mode", "require");
        TestFields.set(config, "configGeneration", 3);
        assertFalse(BedrockPrincipalConfiguration.from(config).isCapable());
    }

    @Test
    void malformedOriginIsNotCapable() {
        ConnectConfig.BedrockPrincipalConfig config = new ConnectConfig().getBedrockPrincipal();
        TestFields.set(config, "configGeneration", 2);
        TestFields.set(config, "mode", "require");
        TestFields.set(config, "issuer", "minekube-connect");
        TestFields.set(config, "trustDomain", "urn:minekube:connect:production");
        TestFields.set(config, "audience", "urn:minekube:connect:bedrock-principal:v2");
        TestFields.set(config, "metadataOrigin", null);
        assertFalse(BedrockPrincipalConfiguration.from(config).isCapable());
    }

    private <T extends ConnectConfig> T load(Class<T> type, Path directory) throws Exception {
        Files.createDirectories(directory);
        return new ConfigLoader(directory, type,
                new ConfigLoader.EndpointNameGenerator(new OkHttpClient.Builder()
                        .addInterceptor(chain -> new okhttp3.Response.Builder()
                                .request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                                .code(200).message("OK")
                                .body(okhttp3.ResponseBody.create(
                                        okhttp3.MediaType.get("text/plain"), "generated"))
                                .build())
                        .build()), mock(ConnectLogger.class)).load();
    }

    private static final class TestFields {
        static void set(Object target, String name, Object value) {
            try {
                var field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }
    }
}
