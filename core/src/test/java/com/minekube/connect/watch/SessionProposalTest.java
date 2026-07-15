package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import minekube.connect.v1alpha1.WatchServiceOuterClass.Authentication;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfile;
import minekube.connect.v1alpha1.WatchServiceOuterClass.GameProfileProperty;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Player;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import org.junit.jupiter.api.Test;

class SessionProposalTest {
    @Test
    void preservesTrustedProtocolMarkerWithoutInferringJava() {
        Session bedrock = Session.newBuilder().setProtocol(SessionProtocol.SESSION_PROTOCOL_BEDROCK).build();
        Session java = Session.newBuilder().setProtocol(SessionProtocol.SESSION_PROTOCOL_JAVA).build();
        Session legacy = Session.getDefaultInstance();
        Session unknown = Session.newBuilder().setProtocolValue(99).build();

        assertEquals(SessionProtocol.SESSION_PROTOCOL_BEDROCK,
                new SessionProposal(bedrock, reason -> {}).getProtocol());
        assertEquals(SessionProtocol.SESSION_PROTOCOL_JAVA,
                new SessionProposal(java, reason -> {}).getProtocol());
        assertEquals(SessionProtocol.SESSION_PROTOCOL_UNSPECIFIED,
                new SessionProposal(legacy, reason -> {}).getProtocol());
        assertEquals(SessionProtocol.UNRECOGNIZED,
                new SessionProposal(unknown, reason -> {}).getProtocol());
    }

    @Test
    void parsesBedrockIdentityScopeSidecarFromLegacyWatchSession() {
        Session session = Session.newBuilder()
                .setId("session-1")
                .setAuth(Authentication.newBuilder().setPassthrough(false))
                .setPlayer(Player.newBuilder()
                        .setAddr("127.0.0.1")
                        .setProfile(GameProfile.newBuilder()
                                .setId("00000000-0000-0000-0000-000000000000")
                                .setName("Player")
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity_scope")
                                        .setValue("{\"endpoint_id\":\"endpoint-id\",\"endpoint_org_id\":\"org-id\"}"))))
                .build();

        SessionProposal proposal = new SessionProposal(session, reason -> {});

        assertEquals("endpoint-id", proposal.getEndpointId());
        assertEquals("org-id", proposal.getEndpointOrgId());
    }

    @Test
    void publicSessionAndLoggingExcludeRawIdentityEnvelope() {
        Session session = Session.newBuilder()
                .setId("session-1")
                .setPlayer(Player.newBuilder()
                        .setProfile(GameProfile.newBuilder()
                                .setId("f912bf90-8349-565f-9dc0-9891923c0cc3")
                                .setName("BedrockSteve")
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("textures")
                                        .setValue("skin"))
                                .addProperties(GameProfileProperty.newBuilder()
                                        .setName("minekube:bedrock_identity")
                                        .setValue("signed-envelope-replay-nonce-a"))))
                .build();

        SessionProposal proposal = new SessionProposal(session, reason -> {});

        assertEquals(1, proposal.getSession().getPlayer().getProfile().getPropertiesCount());
        assertEquals("textures", proposal.getSession().getPlayer().getProfile().getProperties(0).getName());
        assertFalse(proposal.getSession().toString().contains("replay-nonce-a"));
        assertFalse(proposal.toString().contains("replay-nonce-a"));
    }
}
