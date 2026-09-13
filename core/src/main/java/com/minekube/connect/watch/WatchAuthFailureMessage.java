package com.minekube.connect.watch;

final class WatchAuthFailureMessage {
    private static final String TOKEN_ENDPOINT_MISMATCH = "CONNECT_AUTH_TOKEN_ENDPOINT_MISMATCH:";
    private static final String ENDPOINT_ORG_OWNED = "CONNECT_AUTH_ENDPOINT_ORG_OWNED:";

    /**
     * Both auth codes mean the same user-facing condition: the presented token does not match the token
     * stored for this endpoint name. The Watch service reports {@code CONNECT_AUTH_ENDPOINT_ORG_OWNED} for
     * ANY non-matching token when the endpoint name has an organization parent, so the wording must never
     * send the user to fix their organization selection instead of their token.
     */
    private static final String TOKEN_MISMATCH_GUIDANCE =
            "WatchService rejected the endpoint token for this endpoint name: "
                    + "it does not match the token currently stored for this endpoint. "
                    + "If you reset the token in the Minekube dashboard, the previous token is invalidated; "
                    + "put the new token byte-for-byte into the connector token file "
                    + "(token.json in the connector data directory, or the CONNECT_TOKEN environment variable) "
                    + "rather than into config.yml, which only holds the endpoint name, then restart the connector. "
                    + "If you do not own this endpoint name, choose a different endpoint name.";

    private static final String ORG_OWNED_CLAUSE =
            " This endpoint name belongs to an organization, so only a token created for it inside that "
                    + "organization is accepted; a different token cannot take the name over.";

    private WatchAuthFailureMessage() {
    }

    static String format(String responseBody) {
        String message = responseBody == null ? "" : responseBody.trim();
        if (message.startsWith(TOKEN_ENDPOINT_MISMATCH)) {
            return TOKEN_MISMATCH_GUIDANCE;
        }
        if (message.startsWith(ENDPOINT_ORG_OWNED)) {
            return TOKEN_MISMATCH_GUIDANCE + ORG_OWNED_CLAUSE;
        }
        return message;
    }
}
