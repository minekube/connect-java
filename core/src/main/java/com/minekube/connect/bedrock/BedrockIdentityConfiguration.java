package com.minekube.connect.bedrock;

import com.minekube.connect.config.ConnectConfig.BedrockIdentityConfig;

final class BedrockIdentityConfiguration {
    enum Mode { DISABLED, WARN, REQUIRE, INVALID }

    private final Mode mode;
    private final String issuer;
    private final String policy;

    private BedrockIdentityConfiguration(Mode mode, String issuer, String policy) {
        this.mode = mode;
        this.issuer = issuer;
        this.policy = policy;
    }

    static BedrockIdentityConfiguration from(BedrockIdentityConfig config) {
        if (config == null) return new BedrockIdentityConfiguration(Mode.INVALID, null, null);
        String enforcement = config.getEnforcement();
        Mode mode;
        if ("disabled".equals(enforcement)) mode = Mode.DISABLED;
        else if ("warn".equals(enforcement)) mode = Mode.WARN;
        else if ("require".equals(enforcement)) mode = Mode.REQUIRE;
        else mode = Mode.INVALID;
        String issuer = nonBlank(config.getExpectedIssuer()) ? config.getExpectedIssuer() : null;
        String policy = allowedPolicy(config.getExpectedPolicy()) ? config.getExpectedPolicy() : null;
        return new BedrockIdentityConfiguration(mode, issuer, policy);
    }

    Mode mode() { return mode; }
    boolean isDisabled() { return mode == Mode.DISABLED; }
    boolean isUsable() { return (mode == Mode.WARN || mode == Mode.REQUIRE) && issuer != null && policy != null; }
    String issuer() { return issuer; }
    String policy() { return policy; }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static boolean allowedPolicy(String value) {
        return "linked_java_only".equals(value) || "trusted_bedrock_xuid".equals(value);
    }
}
