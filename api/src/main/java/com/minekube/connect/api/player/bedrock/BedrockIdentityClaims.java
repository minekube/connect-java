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
    String nonce;
}
