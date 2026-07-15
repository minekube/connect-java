package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.watch.SessionProposal;
import java.lang.reflect.Field;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import minekube.connect.v1alpha1.WatchServiceOuterClass.Session;
import minekube.connect.v1alpha1.WatchServiceOuterClass.SessionProtocol;
import org.junit.jupiter.api.Test;

class BedrockAdmissionCoordinatorTest {
    @Test
    void proposalGenerationsAreOpaqueAndMonotonic() throws Exception {
        ScheduledThreadPoolExecutor cleanupExecutor = cleanupExecutor();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry(), cleanupExecutor);
        try {
            SessionProposal first = coordinator.proposal(
                    VerifiedBedrockIdentityRegistryTest.session(false), reason -> {}, "", "");
            SessionProposal second = coordinator.proposal(
                    VerifiedBedrockIdentityRegistryTest.session(false), reason -> {}, "", "");
            Field[] tokenFields = first.getAdmissionToken().getClass().getDeclaredFields();

            assertEquals(1, tokenFields.length);
            assertEquals(long.class, tokenFields[0].getType());
            tokenFields[0].setAccessible(true);
            assertTrue(tokenFields[0].getLong(first.getAdmissionToken())
                    < tokenFields[0].getLong(second.getAdmissionToken()));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void delayedOlderProposalCannotReplaceNewerGeneration() {
        ScheduledThreadPoolExecutor cleanupExecutor = cleanupExecutor();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry(), cleanupExecutor);
        try {
            SessionProposal older = coordinator.proposal(
                    VerifiedBedrockIdentityRegistryTest.session(false), reason -> {}, "", "");
            SessionProposal newer = coordinator.proposal(
                    VerifiedBedrockIdentityRegistryTest.session(true), reason -> {}, "", "");

            coordinator.stage(newer);

            assertThrows(IllegalStateException.class, () -> coordinator.stage(older));
            assertEquals(1, cleanupExecutor.getQueue().size());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void coordinatorCreatedJavaProposalHasGenerationToken() {
        ScheduledThreadPoolExecutor cleanupExecutor = cleanupExecutor();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry(), cleanupExecutor);
        try {
            SessionProposal proposal = coordinator.proposal(
                    javaSession("session-1"), reason -> {}, "", "");

            assertNotNull(proposal.getAdmissionToken());
            assertEquals(1, cleanupExecutor.getQueue().size());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void delayedNoIdentityProposalCannotReplaceNewerBedrockGeneration() {
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry(), cleanupExecutor());
        try {
            SessionProposal older = coordinator.proposal(
                    javaSession("session-1"), reason -> {}, "", "");
            SessionProposal newer = coordinator.proposal(
                    VerifiedBedrockIdentityRegistryTest.session(false), reason -> {}, "", "");

            coordinator.stage(newer);

            assertThrows(IllegalStateException.class, () -> coordinator.stage(older));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void noIdentityJavaControlRaceKeepsNewestProposal() {
        ScheduledThreadPoolExecutor cleanupExecutor = cleanupExecutor();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry(), cleanupExecutor);
        try {
            SessionProposal older = coordinator.proposal(
                    javaSession("session-1"), reason -> {}, "", "");
            SessionProposal newer = coordinator.proposal(
                    javaSession("session-1"), reason -> {}, "", "");

            coordinator.stage(newer);

            assertThrows(IllegalStateException.class, () -> coordinator.stage(older));
            assertNotNull(newer.getAdmissionToken());
            assertEquals(1, cleanupExecutor.getQueue().size());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void noIdentityProposalCannotStageOrRegisterAfterClose() {
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(
                new VerifiedBedrockIdentityRegistry(), cleanupExecutor());
        SessionProposal proposal = coordinator.proposal(
                javaSession("session-1"), reason -> {}, "", "");

        coordinator.close();

        assertThrows(IllegalStateException.class, () -> coordinator.stage(proposal));
        assertThrows(IllegalStateException.class, () -> coordinator.proposal(
                javaSession("session-2"), reason -> {}, "", ""));
    }

    private static Session javaSession(String sessionId) {
        Session source = VerifiedBedrockIdentityRegistryTest.session(false);
        return source.toBuilder()
                .setId(sessionId)
                .setProtocol(SessionProtocol.SESSION_PROTOCOL_JAVA)
                .setPlayer(source.getPlayer().toBuilder()
                        .setProfile(source.getPlayer().getProfile().toBuilder().clearProperties()))
                .build();
    }

    private static ScheduledThreadPoolExecutor cleanupExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
