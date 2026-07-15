package com.minekube.connect.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.watch.SessionProposal;
import java.lang.reflect.Field;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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

    private static ScheduledThreadPoolExecutor cleanupExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
