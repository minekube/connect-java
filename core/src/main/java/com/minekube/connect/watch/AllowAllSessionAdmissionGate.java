package com.minekube.connect.watch;

import com.google.inject.Singleton;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Preserves the existing plugin behavior when a platform does not install a private gate.
 */
@Singleton
public final class AllowAllSessionAdmissionGate implements SessionAdmissionGate {
    @Override
    public CompletionStage<SessionAdmissionDecision> request(SessionProposal proposal) {
        return CompletableFuture.completedFuture(SessionAdmissionDecision.allow());
    }
}
