package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WatchAuthFailureMessageTest {
    @Test
    void explainsEndpointTokenMismatch() {
        String message = WatchAuthFailureMessage.format(
                "CONNECT_AUTH_TOKEN_ENDPOINT_MISMATCH: endpoint token does not match the existing endpoint name");

        assertEquals(
                "WatchService rejected the endpoint token for this endpoint name. "
                        + "Regenerate the token for this exact endpoint and organization in the Minekube dashboard, "
                        + "replace the token in token.json or CONNECT_TOKEN, then restart the server. "
                        + "If you do not own this endpoint name, choose a different endpoint name.",
                message);
    }

    @Test
    void explainsOrganizationOwnedEndpoint() {
        String message = WatchAuthFailureMessage.format(
                "CONNECT_AUTH_ENDPOINT_ORG_OWNED: endpoint name belongs to an organization");

        assertEquals(
                "WatchService rejected this endpoint because the endpoint name belongs to an organization. "
                        + "Switch to the owning Minekube organization/team, regenerate or import the endpoint token there, "
                        + "then restart the server.",
                message);
    }

    @Test
    void preservesUnknownServerMessages() {
        assertEquals("Internal Server Error", WatchAuthFailureMessage.format("Internal Server Error"));
    }
}
