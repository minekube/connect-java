package com.minekube.connect.watch;

import java.util.Objects;

/**
 * A safe, asynchronous admission outcome for a Connect session proposal.
 */
public final class SessionAdmissionDecision {
    private static final SessionAdmissionDecision ALLOW =
            new SessionAdmissionDecision(Outcome.ALLOW, "");
    private static final SessionAdmissionDecision DEFER_TO_LOCAL_LOGIN =
            new SessionAdmissionDecision(Outcome.DEFER_TO_LOCAL_LOGIN, "");

    private final Outcome outcome;
    private final String safeMessage;

    private SessionAdmissionDecision(Outcome outcome, String safeMessage) {
        this.outcome = outcome;
        this.safeMessage = safeMessage;
    }

    public static SessionAdmissionDecision allow() {
        return ALLOW;
    }

    public static SessionAdmissionDecision deferToLocalLogin() {
        return DEFER_TO_LOCAL_LOGIN;
    }

    public static SessionAdmissionDecision deny(String safeMessage) {
        String message = Objects.requireNonNull(safeMessage, "safeMessage").trim();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("safeMessage must not be empty");
        }
        return new SessionAdmissionDecision(Outcome.DENY, message);
    }

    public boolean isAllowed() {
        return outcome == Outcome.ALLOW;
    }

    public boolean isDeferredToLocalLogin() {
        return outcome == Outcome.DEFER_TO_LOCAL_LOGIN;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    private enum Outcome {
        ALLOW,
        DEFER_TO_LOCAL_LOGIN,
        DENY
    }
}
