package com.minekube.connect.api.player.bedrock;

import java.time.Instant;
import lombok.Value;

@Value
public class BedrockIdentityClaims {
    String issuer;
    String endpointId;
    String endpointName;
    String orgId;
    String sessionId;
    String protocol;
    String bedrockAuthPolicy;
    String principalType;
    String bedrockXuid;
    String bedrockUsername;
    String bedrockDerivedUuid;
    String linkedJavaUuid;
    String linkedJavaName;
    Instant issuedAt;
    Instant expiresAt;

    public BedrockIdentityClaims(
            String issuer,
            String endpointId,
            String endpointName,
            String orgId,
            String sessionId,
            String protocol,
            String bedrockAuthPolicy,
            String principalType,
            String bedrockXuid,
            String bedrockUsername,
            String bedrockDerivedUuid,
            String linkedJavaUuid,
            String linkedJavaName,
            Instant issuedAt,
            Instant expiresAt) {
        this.issuer = issuer;
        this.endpointId = endpointId;
        this.endpointName = endpointName;
        this.orgId = orgId;
        this.sessionId = sessionId;
        this.protocol = protocol;
        this.bedrockAuthPolicy = bedrockAuthPolicy;
        this.principalType = principalType;
        this.bedrockXuid = bedrockXuid;
        this.bedrockUsername = bedrockUsername;
        this.bedrockDerivedUuid = bedrockDerivedUuid;
        this.linkedJavaUuid = linkedJavaUuid;
        this.linkedJavaName = linkedJavaName;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    @Deprecated
    public BedrockIdentityClaims(
            String issuer,
            String endpointId,
            String endpointName,
            String orgId,
            String sessionId,
            String protocol,
            String bedrockAuthPolicy,
            String principalType,
            String bedrockXuid,
            String bedrockUsername,
            String bedrockDerivedUuid,
            String linkedJavaUuid,
            String linkedJavaName,
            Instant issuedAt,
            Instant expiresAt,
            String ignoredNonce) {
        this(
                issuer,
                endpointId,
                endpointName,
                orgId,
                sessionId,
                protocol,
                bedrockAuthPolicy,
                principalType,
                bedrockXuid,
                bedrockUsername,
                bedrockDerivedUuid,
                linkedJavaUuid,
                linkedJavaName,
                issuedAt,
                expiresAt);
    }

    @Deprecated
    public String getNonce() {
        return null;
    }
}
