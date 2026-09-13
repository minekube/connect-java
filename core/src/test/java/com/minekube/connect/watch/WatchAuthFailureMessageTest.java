package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WatchAuthFailureMessageTest {
    private static final String TOKEN_MISMATCH_GUIDANCE =
            "WatchService rejected the endpoint token for this endpoint name: "
                    + "it does not match the token currently stored for this endpoint. "
                    + "If you reset the token in the Minekube dashboard, the previous token is invalidated; "
                    + "put the new token byte-for-byte into the connector token file "
                    + "(token.json in the connector data directory, or the CONNECT_TOKEN environment variable) "
                    + "rather than into config.yml, which only holds the endpoint name, then restart the connector. "
                    + "If you do not own this endpoint name, choose a different endpoint name.";

    @Test
    void explainsEndpointTokenMismatch() {
        String message = WatchAuthFailureMessage.format(
                "CONNECT_AUTH_TOKEN_ENDPOINT_MISMATCH: endpoint token does not match the existing endpoint name");

        assertEquals(TOKEN_MISMATCH_GUIDANCE, message);
    }

    @Test
    void explainsOrganizationOwnedEndpointAsTokenMismatch() {
        String message = WatchAuthFailureMessage.format(
                "CONNECT_AUTH_ENDPOINT_ORG_OWNED: endpoint name belongs to an organization");

        // The org-owned code is returned for ANY non-matching token on an org-owned name, so the primary
        // condition reported to the user must be the token mismatch, with the ownership as the secondary clause.
        assertEquals(
                TOKEN_MISMATCH_GUIDANCE
                        + " This endpoint name belongs to an organization, so only a token created for it "
                        + "inside that organization is accepted; a different token cannot take the name over.",
                message);
    }

    @Test
    void organizationOwnedMessageDoesNotSendUsersToOrgSwitching() {
        String message = WatchAuthFailureMessage.format(
                "CONNECT_AUTH_ENDPOINT_ORG_OWNED: endpoint name belongs to an organization");

        assertTrue(message.startsWith("WatchService rejected the endpoint token for this endpoint name:"));
        assertFalse(message.contains("Switch to the owning"));
        assertFalse(message.contains("organization/team"));
    }

    @Test
    void bothAuthMessagesDescribeTheTokenFileAndTheConfigSplit() {
        for (String responseBody : new String[] {
                "CONNECT_AUTH_TOKEN_ENDPOINT_MISMATCH: endpoint token does not match the existing endpoint name",
                "CONNECT_AUTH_ENDPOINT_ORG_OWNED: endpoint name belongs to an organization"}) {
            String message = WatchAuthFailureMessage.format(responseBody);

            assertTrue(message.contains("token.json"), responseBody);
            assertTrue(message.contains("CONNECT_TOKEN"), responseBody);
            assertTrue(message.contains("rather than into config.yml"), responseBody);
            assertTrue(message.contains("restart the connector"), responseBody);
        }
    }

    @Test
    void preservesUnknownServerMessages() {
        assertEquals("Internal Server Error", WatchAuthFailureMessage.format("Internal Server Error"));
    }
}
