package com.minekube.connect.api.player.principal;

/** Trusted bindings constructed from the authenticated proposal/session path. */
public final class TrustedProposalContext extends PrincipalBindings {
    public TrustedProposalContext(
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
        super(issuer, trustDomain, audience, endpointId, organizationId, connectSessionId,
                connectSessionNonce, sourceProtocol, sourceProtocolVersion, policyRevision);
    }
}
