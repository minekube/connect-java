package com.minekube.connect.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.minekube.connect.api.logger.ConnectLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {
    @TempDir Path tempDir;

    @Test
    void loadsDocumentedAllowOfflineModePlayersKey() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "endpoint: codexp2p3",
                "allow-offline-mode-players: true",
                "metrics:",
                "  disabled: true",
                "  uuid: 00000000-0000-0000-0000-000000000000",
                "config-version: 1",
                ""));

        ConfigLoader loader = new ConfigLoader(
                tempDir,
                ConnectConfig.class,
                new ConfigLoader.EndpointNameGenerator(new OkHttpClient()),
                mock(ConnectLogger.class));

        ConnectConfig config = loader.load();

        assertEquals(Boolean.TRUE, config.getAllowOfflineModePlayers());
    }

    @Test
    void loadsBedrockIdentityEnforcementConfig() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "endpoint: codexp2p3",
                "allow-offline-mode-players: false",
                "bedrock-identity:",
                "  enforcement: require",
                "  public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "  public-keys:",
                "    - AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                "  metadata-url: https://connect-api.minekube.com/.well-known/minekube-connect/bedrock-identity-keys.json",
                "  metadata-cache-seconds: 120",
                "  expected-policy: trusted_bedrock_xuid",
                "metrics:",
                "  disabled: true",
                "  uuid: 00000000-0000-0000-0000-000000000000",
                "config-version: 1",
                ""));

        ConfigLoader loader = new ConfigLoader(
                tempDir,
                ConnectConfig.class,
                new ConfigLoader.EndpointNameGenerator(new OkHttpClient()),
                mock(ConnectLogger.class));

        ConnectConfig config = loader.load();

        assertEquals("require", config.getBedrockIdentity().getEnforcement());
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", config.getBedrockIdentity().getPublicKey());
        assertEquals("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", config.getBedrockIdentity().getPublicKeys().get(0));
        assertEquals(
                "https://connect-api.minekube.com/.well-known/minekube-connect/bedrock-identity-keys.json",
                config.getBedrockIdentity().getMetadataUrl());
        assertEquals(120, config.getBedrockIdentity().getMetadataCacheSeconds());
        assertEquals("trusted_bedrock_xuid", config.getBedrockIdentity().getExpectedPolicy());
    }

    @Test
    void preservesExplicitQuotedBedrockIdentitySection() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "endpoint: codexp2p3",
                "allow-offline-mode-players: false",
                "\"bedrock-identity\":",
                "  enforcement: require",
                "  metadata-url: https://operator.example/bedrock-identity-keys.json",
                "metrics:",
                "  disabled: true",
                "  uuid: 00000000-0000-0000-0000-000000000000",
                "config-version: 1",
                ""));

        ConnectConfig config = load(ConnectConfig.class);

        assertEquals("require", config.getBedrockIdentity().getEnforcement());
        assertEquals(
                "https://operator.example/bedrock-identity-keys.json",
                config.getBedrockIdentity().getMetadataUrl());
    }

    @Test
    void legacyConfigWithoutBedrockSectionUsesSafeMinekubeIdentityDefaults() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "endpoint: codexp2p3",
                "allow-offline-mode-players: false",
                "metrics:",
                "  disabled: true",
                "  uuid: 00000000-0000-0000-0000-000000000000",
                "config-version: 1",
                ""));

        ConnectConfig config = load(ConnectConfig.class);

        assertEquals("warn", config.getBedrockIdentity().getEnforcement());
        assertEquals(
                "https://watch-connect.minekube.net/.well-known/minekube-connect/bedrock-identity-keys.json",
                config.getBedrockIdentity().getMetadataUrl());
        assertEquals("minekube-connect", config.getBedrockIdentity().getExpectedIssuer());
        assertEquals("trusted_bedrock_xuid", config.getBedrockIdentity().getExpectedPolicy());

        ConnectConfig reloaded = load(ConnectConfig.class);
        assertEquals("warn", reloaded.getBedrockIdentity().getEnforcement(),
                "an automatically rewritten legacy config must keep the safe default after restart");
        assertEquals(
                "https://watch-connect.minekube.net/.well-known/minekube-connect/bedrock-identity-keys.json",
                reloaded.getBedrockIdentity().getMetadataUrl());
    }

    /**
     * The login re-assert is on by default and its full-profile half is off by default, including
     * for a proxy config written before either key existed.
     */
    @Test
    void loginReassertDefaultsToOnAndPropertiesOnly() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "endpoint: codexp2p3",
                "allow-offline-mode-players: false",
                "metrics:",
                "  disabled: true",
                "  uuid: 00000000-0000-0000-0000-000000000000",
                "config-version: 1",
                ""));

        ProxyConnectConfig config = load(ProxyConnectConfig.class);

        assertTrue(config.getLoginReassert().isEnabled(),
                "the re-assert must be on unless an operator turns it off");
        assertFalse(config.getLoginReassert().isRestoreFullProfile(),
                "restoring the UUID needs an operator-side prerequisite, so it must be opt-in");
    }

    /** Both halves are operator-controllable from the config file. */
    @Test
    void loadsLoginReassertOverrides() throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), String.join("\n",
                "endpoint: codexp2p3",
                "allow-offline-mode-players: false",
                "login-reassert:",
                "  enabled: false",
                "  restore-full-profile: true",
                "metrics:",
                "  disabled: true",
                "  uuid: 00000000-0000-0000-0000-000000000000",
                "config-version: 1",
                ""));

        ProxyConnectConfig config = load(ProxyConnectConfig.class);

        assertFalse(config.getLoginReassert().isEnabled());
        assertTrue(config.getLoginReassert().isRestoreFullProfile());
    }

    /**
     * The opt-in is only safe with a documented prerequisite; a default with a hidden one is a
     * footgun. Pin that the prerequisite is stated where the operator reads the option.
     */
    @Test
    void shippedProxyTemplateDocumentsTheFullProfilePrerequisite() throws Exception {
        String template;
        try (java.io.InputStream in =
                     ConfigLoader.class.getResourceAsStream("/proxy-config.yml")) {
            template = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        int optionAt = template.indexOf("restore-full-profile:");
        assertTrue(optionAt > 0, "proxy-config.yml must ship the restore-full-profile option");
        String documentation = template.substring(0, optionAt);
        assertTrue(documentation.contains("new-uuid-creator: MOJANG"),
                "the LibreLogin-side prerequisite must be documented next to the option");
    }

    private <T extends ConnectConfig> T load(Class<T> configClass) {
        return new ConfigLoader(
                tempDir,
                configClass,
                new ConfigLoader.EndpointNameGenerator(new OkHttpClient()),
                mock(ConnectLogger.class)).load();
    }
}
