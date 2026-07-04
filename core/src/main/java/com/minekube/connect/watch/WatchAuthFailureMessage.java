package com.minekube.connect.watch;

final class WatchAuthFailureMessage {
    private static final String TOKEN_ENDPOINT_MISMATCH = "CONNECT_AUTH_TOKEN_ENDPOINT_MISMATCH:";
    private static final String ENDPOINT_ORG_OWNED = "CONNECT_AUTH_ENDPOINT_ORG_OWNED:";

    private WatchAuthFailureMessage() {
    }

    static String format(String responseBody) {
        String message = responseBody == null ? "" : responseBody.trim();
        if (message.startsWith(TOKEN_ENDPOINT_MISMATCH)) {
            return "WatchService rejected the endpoint token for this endpoint name. "
                    + "Regenerate the token for this exact endpoint and organization in the Minekube dashboard, "
                    + "replace the token in token.json or CONNECT_TOKEN, then restart the server. "
                    + "If you do not own this endpoint name, choose a different endpoint name.";
        }
        if (message.startsWith(ENDPOINT_ORG_OWNED)) {
            return "WatchService rejected this endpoint because the endpoint name belongs to an organization. "
                    + "Switch to the owning Minekube organization/team, regenerate or import the endpoint token there, "
                    + "then restart the server.";
        }
        return message;
    }
}
