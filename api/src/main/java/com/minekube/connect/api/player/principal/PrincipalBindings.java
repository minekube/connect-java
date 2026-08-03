package com.minekube.connect.api.player.principal;

import java.util.Arrays;
import java.util.Objects;

/** Authenticated proposal bindings matched by a verified principal. */
public class PrincipalBindings {
    private final String issuer;
    private final String trustDomain;
    private final String audience;
    private final String endpointId;
    private final String organizationId;
    private final String connectSessionId;
    private final transient byte[] connectSessionNonce;
    private final String sourceProtocol;
    private final int sourceProtocolVersion;
    private final long policyRevision;

    public PrincipalBindings(
            String issuer,
            String trustDomain,
            String audience,
            String endpointId,
            String organizationId,
            String connectSessionId,
            byte[] connectSessionNonce,
            String sourceProtocol,
            int sourceProtocolVersion,
            long policyRevision) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.trustDomain = Objects.requireNonNull(trustDomain, "trustDomain");
        this.audience = Objects.requireNonNull(audience, "audience");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.connectSessionId = Objects.requireNonNull(connectSessionId, "connectSessionId");
        this.connectSessionNonce = Objects.requireNonNull(connectSessionNonce, "connectSessionNonce").clone();
        this.sourceProtocol = Objects.requireNonNull(sourceProtocol, "sourceProtocol");
        this.sourceProtocolVersion = sourceProtocolVersion;
        this.policyRevision = policyRevision;
    }

    public String issuer() { return issuer; }
    public String trustDomain() { return trustDomain; }
    public String audience() { return audience; }
    public String endpointId() { return endpointId; }
    public String organizationId() { return organizationId; }
    public String connectSessionId() { return connectSessionId; }
    public byte[] connectSessionNonce() { return connectSessionNonce.clone(); }
    public String sourceProtocol() { return sourceProtocol; }
    public int sourceProtocolVersion() { return sourceProtocolVersion; }
    public long policyRevision() { return policyRevision; }

    @Override
    public String toString() {
        return "PrincipalBindings[issuer=" + issuer + ", trustDomain=" + trustDomain
                + ", audience=" + audience + ", sourceProtocol=" + sourceProtocol
                + ", sourceProtocolVersion=" + sourceProtocolVersion
                + ", policyRevision=" + policyRevision + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PrincipalBindings)) return false;
        PrincipalBindings that = (PrincipalBindings) other;
        return sourceProtocolVersion == that.sourceProtocolVersion
                && policyRevision == that.policyRevision
                && issuer.equals(that.issuer)
                && trustDomain.equals(that.trustDomain)
                && audience.equals(that.audience)
                && endpointId.equals(that.endpointId)
                && organizationId.equals(that.organizationId)
                && connectSessionId.equals(that.connectSessionId)
                && Arrays.equals(connectSessionNonce, that.connectSessionNonce)
                && sourceProtocol.equals(that.sourceProtocol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issuer, trustDomain, audience, endpointId, organizationId,
                connectSessionId, Arrays.hashCode(connectSessionNonce), sourceProtocol,
                sourceProtocolVersion, policyRevision);
    }
}
