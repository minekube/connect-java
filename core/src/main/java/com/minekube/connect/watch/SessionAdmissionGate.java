package com.minekube.connect.watch;

import java.util.concurrent.CompletionStage;

/**
 * Decides whether a structurally valid Connect session may allocate tunnel resources.
 */
@FunctionalInterface
public interface SessionAdmissionGate {
    CompletionStage<SessionAdmissionDecision> request(SessionProposal proposal);
}
