package com.minekube.connect.bedrock;

import com.minekube.connect.config.ConnectConfig.BedrockPrincipalConfig;
import java.net.URI;

/** Closed local generation-2 configuration view. */
final class BedrockPrincipalConfiguration {
    static final String METADATA_PATH = "/.well-known/minekube-connect/bedrock-principal-v2.json";

    private final boolean capable;
    private final boolean required;

    private BedrockPrincipalConfiguration(boolean capable, boolean required) {
        this.capable = capable;
        this.required = required;
    }

    static BedrockPrincipalConfiguration from(BedrockPrincipalConfig config) {
        if (config == null) return new BedrockPrincipalConfiguration(false, false);
        boolean required = config.getConfigGeneration() == 2 && "require".equals(config.getMode());
        boolean capable = required
                && bounded(config.getIssuer(), 128)
                && bounded(config.getTrustDomain(), 256)
                && bounded(config.getAudience(), 256)
                && validOrigin(config.getMetadataOrigin(), config.getTrustDomain())
                && METADATA_PATH.equals(config.getMetadataPath());
        return new BedrockPrincipalConfiguration(capable, required);
    }

    boolean isCapable() {
        return capable;
    }

    boolean isRequired() {
        return required;
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isEmpty()
                && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maximum;
    }

    private static boolean validOrigin(String value, String trustDomain) {
        if (value == null) return false;
        try {
            URI origin = URI.create(value);
            boolean valid = "https".equals(origin.getScheme())
                    && origin.getHost() != null
                    && origin.getRawUserInfo() == null
                    && origin.getPort() == -1
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null;
            if (!valid) return false;
            return !"urn:minekube:connect:production".equals(trustDomain)
                    || "https://connect.minekube.com".equals(value);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
