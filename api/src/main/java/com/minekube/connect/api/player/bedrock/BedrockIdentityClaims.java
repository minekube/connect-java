package com.minekube.connect.api.player.bedrock;

import java.time.Instant;
import lombok.Value;

/**
 * Immutable claims decoded from a signed Bedrock identity envelope. Claims returned by
 * {@code ConnectApi#getVerifiedBedrockIdentity} have passed Connect's admission checks and are
 * bound to the current Connect session.
 */
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

    /**
     * Creates an immutable Bedrock identity claim set.
     *
     * @param issuer signed envelope issuer
     * @param endpointId signed Connect endpoint ID
     * @param endpointName signed Connect endpoint name
     * @param orgId signed Connect organization ID
     * @param sessionId signed Connect session ID
     * @param protocol signed client protocol
     * @param bedrockAuthPolicy signed Bedrock authentication policy
     * @param principalType signed principal type
     * @param bedrockXuid canonical Bedrock XUID
     * @param bedrockUsername Bedrock username
     * @param bedrockDerivedUuid deterministic UUID derived from the XUID
     * @param linkedJavaUuid linked Java UUID, or {@code null} for an unlinked principal
     * @param linkedJavaName linked Java name, or {@code null} for an unlinked principal
     * @param issuedAt envelope issue time
     * @param expiresAt envelope expiration time
     */
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

    /**
     * @deprecated The nonce is private replay-protection data and is deliberately not retained.
     *     This constructor remains only for source compatibility; {@code ignoredNonce} is ignored.
     *
     * @param issuer signed envelope issuer
     * @param endpointId signed Connect endpoint ID
     * @param endpointName signed Connect endpoint name
     * @param orgId signed Connect organization ID
     * @param sessionId signed Connect session ID
     * @param protocol signed client protocol
     * @param bedrockAuthPolicy signed Bedrock authentication policy
     * @param principalType signed principal type
     * @param bedrockXuid canonical Bedrock XUID
     * @param bedrockUsername Bedrock username
     * @param bedrockDerivedUuid deterministic UUID derived from the XUID
     * @param linkedJavaUuid linked Java UUID, or {@code null} for an unlinked principal
     * @param linkedJavaName linked Java name, or {@code null} for an unlinked principal
     * @param issuedAt envelope issue time
     * @param expiresAt envelope expiration time
     * @param ignoredNonce ignored legacy nonce
     */
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

    /**
     * @deprecated The nonce is private replay-protection data and is no longer exposed.
     *
     * @return always {@code null}
     */
    @Deprecated
    public String getNonce() {
        return null;
    }
}
