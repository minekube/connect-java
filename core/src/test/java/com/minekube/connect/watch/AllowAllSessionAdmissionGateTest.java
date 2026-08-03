package com.minekube.connect.watch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AllowAllSessionAdmissionGateTest {
    @Test
    void defaultGateAllowsImmediately() throws Exception {
        SessionAdmissionDecision decision = new AllowAllSessionAdmissionGate()
                .request(mock(SessionProposal.class))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);

        assertTrue(decision.isAllowed());
    }
}
